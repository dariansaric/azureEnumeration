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


// todo : enumerate resource groups
// todo : enumerate active directory
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

    for(group in adGroups) {
        def g = ad.createChild(group.displayName)
        def att = g.getAttributes()
        att
        g["objectId"] = group.objectId
        g["mailNickname"] = group.mailNickname
        g["securityID"] = group.securityID
        g["samAccountName"] = group.samAccountName
        enumerateADGroupMembers(g, 1)
    }
}
/**
 * Enumerates Active Directory group members for an Active Directory group specified by the provided node.
 *
 * @param adGroupNode Active Directory group node
 * @param descentLevel level of recursion (if a member is an Active Directory group)
 * @throws Exception if anything goes wrong
 */
def enumerateADGroupMembers(adGroupNode, descentLevel) throws Exception{
    def groupObjectId = adGroupNode["objectId"]
    def getADGroupMembersCmd = "az ad group member list -g $groupObjectId |" +
            "jq '[.[] | {displayName: .displayName, mailNickname: .mailNickname," +
            "onPremisesSecurityID: .onPremisesSecurityIdentifier, userType:.userType, userPrincipalName: .userPrincipalName}]'"
    def members = getJSONFromCmd(getADGroupMembersCmd)
    if (members.isEmpty()) {
        return
    }

    adGroupNode["numberOfMembers"] = members.size
    for(member in members) {
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
}

def enumerateVMs(resourceGroupNode) {
    def vmsNode = resourceGroupNode.createChild(VM_NODE_NAME)
    def getVMBasicCmd = "az vm list -g $resourceGroupNode.name | jq '[.[] | {id:.id, vmId:.vmId, name:.name," +
            "networkInterfaces:.networkProfile.networkInterfaces,image:.storageProfile.imageReference, osDisk:.storageProfile.osDisk}]'"
    def VMBasicInfos = getJSONFromCmd(getVMBasicCmd)
    for(vm in VMBasicInfos) {
        def vmNode = vmsNode.createChild(vm.name)
        vmNode["id"] = vm.id
        vmNode["vmId"] = vm.Id
        //todo : obradi slozene podatke
    }
}
// todo : enumerate VMs
// todo : enumerate key vaults
// todo : enumerate storage
// todo : enumerate networks


authenticate(node.map.getRoot())