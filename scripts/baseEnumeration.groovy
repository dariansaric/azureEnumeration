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
    ui.informationMessage("Found " + rgroups.size + " resource groups...")
    rgroupsNode["noResourceGroups"] = rgroups.size
    for (rgroup in rgroups) {
        def rg = rgroupsNode.createChild(rgroup.name)
        rg["id"] = rgroup.id
        rg["location"] = rgroup.location
        rg["tags"] = rgroup.tags
    }
    rgroupsNode.setFolded(true)
}

def enumerateVMs(resourceGroupNode) {
    def getVMBasicCmd = "az vm list -g $resourceGroupNode.name | jq '[.[] | {id:.id, vmId:.vmId, name:.name," +
            "networkInterfaces:.networkProfile.networkInterfaces,image:.storageProfile.imageReference, osDisk:.storageProfile.osDisk}]'"
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
        def netIntsNode = vmNode.createChild("Network Interfaces")
//        ui.informationMessage("Found $vm.networkInterfaces.size network interfaces")
        for (i = 0; i < vm.networkInterfaces.size; i++) {
            def nInt = vm.networkInterfaces.get(i)
            def nIntNode = netIntsNode.createChild("Net Interface $i")
            nIntNode["id"] = nInt.id
//            nIntNode
        }

        def imageNode = vmNode.createChild("Image")
        imageNode["exactVersion"] = vm.image.exactVersion
        imageNode.createChild("$vm.image.offer-$vm.image.publisher-$vm.image.sku")
    }
    vmsNode.setFolded(true)
}
// todo : enumerate key vaults
// todo : enumerate storage
// todo : enumerate networks
// todo : na kraju obrade, nadgrupu treba foldati

authenticate(node.map.getRoot())