package com.canonicalidentity.directoryservice

import com.canonicalidentity.directoryservice.listener.InMemoryDirectoryServer
import com.unboundid.ldap.sdk.Entry
import groovy.util.GroovyTestCase

/**
 * See the API for {@link grails.test.mixin.services.ServiceUnitTestMixin} for usage instructions
 */
class DirectoryServiceEntryEntryTests extends GroovyTestCase {

    /*
     * Items needed for pulling in the configuration for both the
     * InMemoryServer and the DirectoryService.
     */
    def dsConfigFile = "conf/Config.groovy"
    def inMemServerConfigFile = "conf/InMemoryServerConfig.groovy"
    def inMemServerConfig = new ConfigSlurper().parse(new File(inMemServerConfigFile).toURL())
    

    /* DirectoryService to use for all tests. */
    def directoryService = new DirectoryService(dsConfigFile)

    /* DirectoryServiceEntry */
    def dse

    /* InMemoryDirectoryServer */
    def inMemServer

    protected void setUp() {
        inMemServer = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            inMemServerConfig.directoryservice.sources.directory,
            "src/test/ldif/schema/directory-schema.ldif",
            "src/test/ldif/directory.ldif"
        )

        // Set up lse to be the person with uid=1
        dse = directoryService.findPersonWhere('uid': '6')
    }

    protected void tearDown() {
        inMemServer?.shutDown()
    }

    void testNewEntryFromMap() {
        def attrs = [
            givenName: 'test',
            sn: 'user',
            cn: ['test user', 'user, test'],
            mail: 'test.user@someu.edu',
            uid: 'test.user123'
        ]
        def entry = new DirectoryServiceEntry(attrs, 'person', directoryService)
        assertNotNull entry.searchResultEntry

        def conn = directoryService.connection(entry.baseDN)
        conn.add(entry.entry)

        def testUser = directoryService.findPersonWhere(uid: 'test.user123')
        assertNotNull testUser
        assertEquals testUser.cn, 'test user'
    }

    void testNewEntryFailsWhenDitItemDoesNotExist() {
        try {
            def entry = new DirectoryServiceEntry([:], 'doesnotexist', directoryService)
        }
        catch (Exception e) {
            assertEquals e.getMessage(), "Could not find the dit map that corresponds to the passed in 'doesnotexist' ditItem."
        }
    }

    void testNewEntryFailsWhenAttrsDoesNotContainRDNAttr() {
        try {
            def entry = new DirectoryServiceEntry([hi: 'there'], 'person', directoryService)
        }
        catch (Exception e) {
            assertEquals e.getMessage(), "Could not find the RDN attribute 'uid' in the passed in attributes."
        }
    }

    void testNewEntryFailsWhenNoObjectClasses() {
        try {
            def entry = new DirectoryServiceEntry([uid: 'test.user'], 'personWithoutOCs', directoryService)
        }
        catch (Exception e) {
            assertEquals e.getMessage(), "Could not find 'objectClass' values in either the passed in attributes or the dit map."
        }
    }

    void testNewEntryFailsWhenObjectClassesIsAString() {
        def attrs = [
            givenName: 'test',
            sn: 'user',
            cn: ['test user', 'user, test'],
            mail: 'test.user@someu.edu',
            uid: 'test.user123',
            objectClass: 'top'
        ]
        try {
            def entry = new DirectoryServiceEntry(attrs, 'person', directoryService)
        }
        catch (Exception e) {
            assertEquals e.getMessage(), "objectClass values must be a list."
        }
    }

    void testNewEntryWorksWhenObjectClassesPassedInAttrs() {
        def attrs = [
            givenName: 'test',
            sn: 'user',
            cn: ['test user', 'user, test'],
            mail: 'test.user@someu.edu',
            uid: 'test.user123',
            objectClass: ['top', 'person', 'inetOrgPerson', 'organizationalPerson']
        ]

        def entry = new DirectoryServiceEntry(attrs, 'personWithoutOCs', directoryService)
        assertNotNull entry
        assertEquals entry.cn, 'test user'
        assertEquals entry.cnValues()?.contains('user, test'), true
        assertEquals entry.entry.getAttributeValues('objectClass').size(), 4
    }

    void testNewEntryUsesFirstRDNValueFromList() {
        def attrs = [
            givenName: 'test',
            sn: 'user',
            cn: ['test user', 'user, test'],
            mail: 'test.user@someu.edu',
            uid: ['test user', 'user, test']
        ]

        def entry = new DirectoryServiceEntry(attrs, 'person', directoryService)
        assertNotNull entry
        assertEquals entry.dn, 'uid=test user,ou=people,dc=someu,dc=edu'
        assertEquals entry.cn, 'test user'
        assertEquals entry.uidValues().size(), 2
        assertEquals entry.cnValues()?.contains('user, test'), true
        assertEquals entry.entry.getAttributeValues('objectClass').size(), 4
    }

}