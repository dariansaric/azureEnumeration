import groovy.json.JsonSlurper

/**
 * This script enumerates basic resources within an Azure cloud
 * For this script to run properly, the root node must have following attributes:
 *  - Azure username
 *  - Azure password
 *  - optional (not yet decided): path to more complex scripts
 */

ACTIVE_DIRECTORY_NODE_NAME = "Active Directory"
RESOURCE_GROUPS_NODE_NAME = "Resource Groups"
VM_NODE_NAME = "Virtual Machines"
NETWORK_INTERFACES_NODE_NAME = "Network Interfaces"
DISK_IMAGE_NODE_NAME = "Image"
KEY_VAULTS_NODE_NAME = "Key Vaults"
KEY_VAULT_ATTRIBUTES_NODE_NAME = "Attributes"
KEY_VAULT_URI_NODE_NAME = "URI"
KEY_VAULT_ACCESS_POLICIES_NODE_NAME = "Access Policies"
KEY_VAULT_CERTIFICATES_PERM_NODE_NAME = "Certificates"
KEY_VAULT_KEYS_PERM_NODE_NAME = "Keys"
KEY_VAULT_SECRETS_PERM_NODE_NAME = "Secrets"
KEY_VAULT_STORAGE_PERM_NODE_NAME = "Storage"
jsonSlurper = new JsonSlurper()
/**
 * Executes a bash command and returns the result as a JSON string.
 *
 * @param cmd bash command in text
 * @return JSON output from the command
 */
def getJSONFromCmd(cmd) {
    Process p = ['bash', '-c', cmd].execute()
    def out = new StringBuffer()
    def err = new StringBuffer()

    p.consumeProcessOutput(out, err)
    p.waitFor()
    if (err.length() != 0) {
        ui.errorMessage(err)
        return null
    }

    return jsonSlurper.parseText(out.toString())
}
/**
 * Extracts a resource's name from it's ID.
 * @param id resource ID
 * @return resource name
 */
static def extractResourceNameFromId(id) {
    return id.substring(id.lastIndexOf("/") + 1)
}
/**
 * Authenticates to Azure cloud with credentials provided as attributes to the root node.
 * @param root map root node
 */
def authenticate(root) {
    def username = root.attributes.getFirst("username")
    def pass = root.attributes.getFirst("pass")
//    def out = new StringBuffer()
    def err = new StringBuffer()
    Process p = "az login -u $username -p $pass".execute()
    p.consumeProcessOutput(new StringBuffer(), err)
    p.waitFor()
    if (err.length() != 0) {
        // neuspjesno logiranje
        ui.errorMessage("Unable to authenticate to Azure as user '$username'! Check your credentials and try again!")
    } else {
        ui.informationMessage("Successfully authenticated to Azure as '$username'...")
        // traži se JOptionPane tip poruke
    }
}

/**
 * Enumerates all obtainable Active Directory groups.
 *
 * @param root map root node
 */
def enumerateADGroups(root) {
    def final getADGroupsCmd = "az ad group list | jq '[.[] |" +
            "{displayName: .displayName, mailNickname: .mailNickname, securityID: .onPremisesSecurityIdentifier, samAccountName: .onPremisesSamAccountName, objectId: .objectId}]'"
    def adGroups = getJSONFromCmd(getADGroupsCmd)
    def ad = root.createChild(ACTIVE_DIRECTORY_NODE_NAME)
    ui.informationMessage("Found " + adGroups.size + " AD groups...")

    for (group in adGroups) {
        def g = ad.createChild(group.displayName)
        def att = g.getAttributes()
        att
        g["objectId"] = group.objectId
        g["mailNickname"] = group.mailNickname
        g["securityID"] = group.securityID
        g["samAccountName"] = group.samAccountName
        enumerateADGroupMembers(g, 1)
    }
    ad.setFolded(true)
}
/**
 * Enumerates Active Directory group members for an Active Directory group specified by the provided node.
 *
 * @param adGroupNode Active Directory group node
 * @param descentLevel level of recursion (if a member is an Active Directory group)
 * @throws Exception if anything goes wrong
 */
def enumerateADGroupMembers(adGroupNode, descentLevel) throws Exception {
    def groupObjectId = adGroupNode["objectId"]
    def getADGroupMembersCmd = "az ad group member list -g $groupObjectId |" +
            "jq '[.[] | {displayName: .displayName, mailNickname: .mailNickname," +
            "onPremisesSecurityID: .onPremisesSecurityIdentifier, userType:.userType, userPrincipalName: .userPrincipalName}]'"
    def members = getJSONFromCmd(getADGroupMembersCmd)
    if (members.isEmpty()) {
        return
    }

    adGroupNode["numberOfMembers"] = members.size
    for (member in members) {
        def m = adGroupNode.createChild(member.displayName)
        m["mailNickname"] = member.mailNickname
        m["objectType"] = member.objectType
        m["onPremSecurityId"] = member.onPremisesSecurityID
        m["userType"] = member.userType
        m["userPrincipalName"] = member.userPrincipalName
        // todo: ako je objekt grupa, onda rekurzivno pozovi (do n razina?)
        if (descentLevel >= 0) {
            enumerateADGroupMembers(m, descentLevel - 1)
        }
        m.setFolded(true)
    }
}
/**
 * Enumerates all accessible resource groups.
 *
 * @param root map root node
 */
def enumerateResourceGroups(root) {
    def getResourceGroupsCmd = "az group list | jq 'sort_by(.name) | .'"
    def rgroups = getJSONFromCmd(getResourceGroupsCmd)
    def rgroupsNode = root.createChild(RESOURCE_GROUPS_NODE_NAME)
    ui.informationMessage("Found $rgroups.size resource groups...")
    rgroupsNode["noResourceGroups"] = rgroups.size
    for (rgroup in rgroups) {
        def rg = rgroupsNode.createChild(rgroup.name)
        rg["id"] = rgroup.id
        rg["location"] = rgroup.location
        rg["tags"] = rgroup.tags
    }
    rgroupsNode.setFolded(true)
}
/**
 * Enumerates Virtual machines within a specified resource group.
 *
 * @param resourceGroupNode resource group
 */
def enumerateVMs(resourceGroupNode) {
    def getVMBasicCmd = "az vm list -g $resourceGroupNode.name | jq '[.[] | {id:.id, vmId:.vmId, name:.name," +
            "networkInterfaces:.networkProfile.networkInterfaces,image:.storageProfile.imageReference" +
            "osDisk:.storageProfile.osDisk}]'"
    def VMBasicInfos = getJSONFromCmd(getVMBasicCmd)
    if (VMBasicInfos.isEmpty) {
        return
    }
    def vmsNode = resourceGroupNode.createChild(VM_NODE_NAME)
    vmsNode["noVMs"] = VMBasicInfos.size
    for (vm in VMBasicInfos) {
        def vmNode = vmsNode.createChild(vm.name)
        vmNode["id"] = vm.id
        vmNode["vmId"] = vm.Id
        def netIntsNode = vmNode.createChild(NETWORK_INTERFACES_NODE_NAME)
        for (i = 0; i < vm.networkInterfaces.size; i++) {
            def nInt = vm.networkInterfaces.get(i)
            def nIntNode = netIntsNode.createChild("Net Interface $i")
            nIntNode["id"] = nInt.id
        }

        def imageNode = vmNode.createChild(DISK_IMAGE_NODE_NAME)
        imageNode["exactVersion"] = vm.image.exactVersion
        imageNode.createChild("$vm.image.offer-$vm.image.publisher-$vm.image.sku")
    }
    vmsNode.setFolded(true)
}
// todo : enumerate vm network security rules
/**
 * Enumerates key vaults in the specified resource group
 *
 * @param resourceGroupNode resource group node
 */
def enumerateKeyVaults(resourceGroupNode) {
    def keyvaultsCmd = "az keyvault list -g $rgroup.name | jq '[.[] | {id:.id, name: .name, location:.location," +
            "accessPolicies: .properties.accessPolicies, uri: .properties.vaultUri}]'"
    def keyvaults = getJSONFromCmd(keyvaultsCmd)
    if (keyvaults.size == 0) {
        return
    }
    def keyVaults = resourceGroupNode.createChild(KEY_VAULTS_NODE_NAME)
    for (vault in keyvaults) {
        def v = keyVaults.createChild(vault.name)
        v["id"] = vault.id
        v["location"] = vault.location
        v["tags"] = vault.tags
        v["type"] = vault.type
        def att = v.createChild(KEY_VAULT_ATTRIBUTES_NODE_NAME)
        def uris = att.createChild(KEY_VAULT_URI_NODE_NAME)
        uris.createChild(vault.uri)
        def accessPolicies = att.createChild(KEY_VAULT_ACCESS_POLICIES_NODE_NAME)
        for (accessPolicy in vault.accessPolicies) {
            //todo primjeni OO strategija
            def ap = accessPolicies.createChild(accessPolicy.objectId)
            def cert = ap.createChild(KEY_VAULT_CERTIFICATES_PERM_NODE_NAME)
            accessPolicy.permissions.certificates.each { cert.createChild(it) }
            def keys = ap.createChild(KEY_VAULT_KEYS_PERM_NODE_NAME)
            accessPolicy.permissions.keys.each { keys.createChild(it) }
            def secrets = ap.createChild(KEY_VAULT_SECRETS_PERM_NODE_NAME)
            accessPolicy.permissions.secrets.each { secrets.createChild(it) }
            def storage = ap.createChild(KEY_VAULT_STORAGE_PERM_NODE_NAME)
            accessPolicy.permissions.storage.each { storage.createChild(it) }
        }
    }
}
/**
 * Enumerates keys in the specified key vault.
 * @param keyVaultNode key vault node
 */
def enumerateKeys(keyVaultNode) {
    def keysCmd = "az keyvault key list --vault-name $keyVaultNode.name | jq '[.[] | .kid]'"
    def keyIds = getJSONFromCmd(keysCmd)
    if (keyIds.size == 0) {
        return
    }
    def keysNode = keyVaultNode.createChild("Keys")
    for (id in keyIds) {
        def keyNode = keysNode.createChild(extractResourceNameFromId(id))
        def keyCmd = "az keyvault key show --vault-name $keyVaultNode.name --id $id"
        def key = getJSONFromCmd(keyCmd)
        keyNode["created"] = key.attributes.created
        keyNode["enabled"] = key.attributes.enabled
        keyNode["expires"] = key.attributes.expires
        keyNode["updated"] = key.attributes.updated
        keyNode["kty"] = key.key.kty
        //todo ostali parametri kljuca
    }
}
// todo : enumerate secrets
// todo : enumerate certificates
// todo : enumerate storage
// todo : enumerate networks


// todo : na kraju obrade, nadgrupu treba foldati
authenticate(node.map.getRoot())