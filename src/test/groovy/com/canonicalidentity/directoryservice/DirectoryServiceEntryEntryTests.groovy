package com.canonicalidentity.directoryservice

import com.canonicalidentity.directoryservice.listener.InMemoryDirectoryServer
import com.unboundid.ldap.sdk.Entry
import groovy.test.GroovyTestCase

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

    void testNewEntryWorksWithPassedInEntry() {
        def ubidEntry = new Entry('uid=testing,ou=people,dc=someu,dc=edu')

        def entry = new DirectoryServiceEntry(ubidEntry, directoryService)
        assertNotNull entry
        assertEquals entry.baseDN, 'ou=people,dc=someu,dc=edu'
    }

    void testNewEntryWorksWithPassedInEntryAndDitItem() {
        def ubidEntry = new Entry(
            'dn: cn=testing,ou=something...,dc=someu,dc=edu',
            'uid: testing',
            'objectClass: something',
            'objectClass: somethingElse'
        )

        def entry = new DirectoryServiceEntry(ubidEntry, 'person', directoryService)
        assertNotNull entry
        assertEquals entry.baseDN, 'ou=people,dc=someu,dc=edu'
        assertEquals entry.dn, 'uid=testing,ou=people,dc=someu,dc=edu'
        
        def objectClasses = entry.entry.getAttributeValues('objectClass')
        assertFalse objectClasses.contains('something')
        assertFalse objectClasses.contains('somethingElse')
        assertEquals objectClasses.size(), 4
    }

    void testNewEntryFailsWhenPassedInEntryHasInvalidDN() {
        def ubidEntry = new Entry('uid=testing,ou=blah,dc=someu,dc=edu')

        try {
            def entry = new DirectoryServiceEntry(ubidEntry, directoryService)
        }
        catch (Exception e) {
            assertEquals e.getMessage(), "Could not find the dit map that corresponds to the parent DN 'ou=blah,dc=someu,dc=edu' of the passed in entry."
        }
    }

    void testDiffingDSEntries() {
        def updatedUid0Map = [
            givenName: 'John',
            sn: 'Smith II',
            cn: ['Smith, John', 'John Smith'],
            displayName: 'John Smith II',
            initials: 'JDS',
            generationalTitle: 'II',
            uid: '0'
        ]

        def attrsToCompare = ['givenName', 'sn', 'cn', 'displayName', 'initials', 'generationalTitle', 'uid']

        def updatedUid0 = new DirectoryServiceEntry(updatedUid0Map, 'person', directoryService)
        def uid0        = directoryService.findPersonWhere(uid: '0')

        // Passes in the DirectoryServiceEntry object
        uid0.modifyToMatch(updatedUid0, attrsToCompare)
        
        assertNotNull uid0.modifications
        assertEquals uid0.modifications.size(), 5

    }

    void testDiffingUBIDEntries() {
        def updatedUid0Map = [
            givenName: 'John',
            sn: 'Smith II',
            cn: ['Smith, John', 'John Smith'],
            displayName: 'John Smith II',
            generationalTitle: 'II',
            uid: '0'
        ]

        def attrsToCompare = ['givenName', 'sn', 'cn', 'displayName', 'initials', 'generationalTitle', 'uid']

        def updatedUid0 = new DirectoryServiceEntry(updatedUid0Map, 'person', directoryService)
        def uid0        = directoryService.findPersonWhere(uid: '0')

        // Passes in the underlying Entry object
        uid0.modifyToMatch(updatedUid0.entry, attrsToCompare)

        assertNotNull uid0.modifications
        assertEquals uid0.modifications.size(), 6 // removed initials

    }

    /**
     * modifyToMatch() must update the entry itself, so that it is dirty, reads
     * back the new values, and still matches the directory after a save (a
     * second edit and save must work).
     */
    void testModifyToMatchUpdatesEntryAndSaves() {
        def attrsToCompare = ['sn', 'displayName', 'initials']
        def updated = new DirectoryServiceEntry([
            uid: '0', sn: 'Smith II', displayName: 'John Smith II'
        ], 'person', directoryService)

        def uid0 = directoryService.findPersonWhere(uid: '0')
        uid0.modifyToMatch(updated, attrsToCompare)

        assertTrue uid0.isDirty()
        assertTrue uid0.isDirty('sn')
        assertTrue uid0.isDirty('initials') // removed
        assertEquals 'Smith II', uid0.sn
        assertNull uid0.initials

        assertTrue directoryService.save(uid0)
        assertFalse uid0.isDirty()

        def reread = directoryService.findPersonWhere(uid: '0')
        assertEquals 'Smith II', reread.sn
        assertEquals 'John Smith II', reread.displayName
        assertNull reread.initials

        // A second edit computes its modifications against what was saved.
        uid0.displayName = 'Johnny Smith II'
        assertTrue directoryService.save(uid0)
        assertEquals 'Johnny Smith II',
            directoryService.findPersonWhere(uid: '0').displayName
    }

    /**
     * Unsaved edits made before modifyToMatch() are kept and saved with it.
     */
    void testModifyToMatchKeepsUnsavedEdits() {
        def updated = new DirectoryServiceEntry([uid: '0', sn: 'Smith III'],
            'person', directoryService)

        def uid0 = directoryService.findPersonWhere(uid: '0')
        uid0.mail = 'john.smith.iii@someu.edu'
        uid0.modifyToMatch(updated, ['sn'])

        assertTrue uid0.isDirty('mail')
        assertTrue uid0.isDirty('sn')
        assertTrue directoryService.save(uid0)

        def reread = directoryService.findPersonWhere(uid: '0')
        assertEquals 'Smith III', reread.sn
        assertEquals 'john.smith.iii@someu.edu', reread.mail
    }

    /**
     * When nothing differs, modifyToMatch() makes no modifications.
     */
    void testModifyToMatchWithNoDifferences() {
        def uid0 = directoryService.findPersonWhere(uid: '0')
        def same = directoryService.findPersonWhere(uid: '0')
        uid0.modifyToMatch(same, ['sn', 'givenName', 'cn'])

        assertFalse uid0.isDirty()
        assertTrue uid0.modifications.isEmpty()
    }

}
