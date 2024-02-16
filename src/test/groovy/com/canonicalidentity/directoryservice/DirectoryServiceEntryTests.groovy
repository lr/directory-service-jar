package com.canonicalidentity.directoryservice

import com.canonicalidentity.directoryservice.listener.InMemoryDirectoryServer
import com.unboundid.ldap.sdk.Entry
import groovy.util.GroovyTestCase

/**
 * See the API for {@link grails.test.mixin.services.ServiceUnitTestMixin} for usage instructions
 */
class DirectoryServiceEntryTests extends GroovyTestCase {

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

    /**
     * Test that the underlying Entry still works.
     */
    void testUnboundIDEntry() {
        assertEquals dse.entry.getDN(), 'uid=6,ou=people,dc=someu,dc=edu'
        assertEquals dse.entry.getAttributeValueAsDate('someuEduEmpExpDate').toString(),
                'Fri Dec 31 18:59:59 EST 9999'
    }

    /**
     * Test getAttributeValue from the set entry object.
     */
    void testGetAttributeValue() {
        assertEquals dse.getAttributeValue('sn'), 'Nguyen'
    }

    /**
     * Test getAttributeValues from the set entry object.
     */
    void testGetAttributeValues() {
        assertEquals dse.getAttributeValues('cn').size(), 2
    }

    /**
     * Test values from the set entry object.
     */
    void testValues() {
        assertEquals dse.cnValues().size(), 2
    }

    /**
     * Test invoke getValue using attribute name as method.
     */
    void testAttributeNameAsMethod() {
        assertEquals dse.dn(), 'uid=6,ou=people,dc=someu,dc=edu'
        assertEquals dse.sn(), 'Nguyen'
        assertEquals dse.givenName(), 'Julie'
    }

    /**
     * Test invoke getValue using attribute name as property.
     */
    void testAttributeNameAsProperty() {
        assertEquals dse.dn, 'uid=6,ou=people,dc=someu,dc=edu'
        assertEquals dse.sn, 'Nguyen'
        assertEquals dse.givenName, 'Julie'
    }

    /**
     * Test attribute as Date.
     */
    void testAttributeAsDate() {
        assertEquals dse.someuEduEmpExpDateAsDate().toString(),
                'Fri Dec 31 18:59:59 EST 9999'
    }

    /**
     * Test attribute as Boolean.
     */
    void testAttributeAsBoolean() {
        assertTrue dse.someuEduFacultyFlagAsBoolean()
        assertTrue Boolean.parseBoolean(dse.someuEduFacultyFlag)
    }

    /**
     * Test isDirty()
     */
    void testIsDirty() {
        assertFalse dse.isDirty()
        assertFalse dse.isDirty('mail')
    }

    /**
     * Simple test to make sure the Entry is really and entry, and not a
     * SearchResultEntry, as SearchResultEntry objects do not allow mods.
     */
    void testSetOfEntry() {
        assert dse.entry instanceof Entry
        dse.entry.setAttribute('mail', 'new.name@someu.edu')
    }

    /**
     * Test propertyMissing() with a single value.
     */
    void testPropertyMissingSingleVal() {
        assertEquals dse.mail, 'Julie.Nguyen@someu.edu'
        dse.mail = 'new.name@someu.edu'
        assertTrue dse.isDirty()
        assertTrue dse.isDirty('mail')
        assertEquals dse.mail, 'new.name@someu.edu'
        assertEquals dse.mods['mail'], 'new.name@someu.edu'
        assertEquals dse.modifications.size(), 2
        assertEquals dse.modifications.get(0).getValues().length, 1
    }

    /**
     * Test propertyMissing with multiple values.
     */
    void testPropertyMissingMultipleVals() {
        assertEquals dse.mail, 'Julie.Nguyen@someu.edu'
        dse.mail = ['new.name@someu.edu', 'another.email@someu.edu']
        assertTrue dse.isDirty()
        assertTrue dse.isDirty('mail')
        assertEquals dse.mail, 'new.name@someu.edu'
        assertEquals dse.mailValues(), ['new.name@someu.edu', 'another.email@someu.edu']
        assertEquals dse.modifications.size(), 2
        //assertEquals dse.modifications.get(0).getValues().length, 2

        dse.cn = ['Julie Nguyen', 'Nguyen, Julie', 'Julie A Nguyen', 'Nguyen, Julie A']
        assertEquals dse.modifications.size(), 3
        assertEquals dse.modifications.get(0).getValues().length, 2
        assertEquals dse.getAttributeValues('cn').length, 4
    }

    /**
     * Test adding attribute that does not exist in the entry. The number of
     * attributes is 28 because we are getting operational attributes.
     */
    void testPropertyMissingAddAttribute() {
        assertEquals dse.entry.getAttributes().size(), 28
        dse.carLicense = 'B12345C'
        assertEquals dse.modifications.size(), 1
        assertEquals dse.entry.getAttributes().size(), 29
        assertEquals dse.carLicense, 'B12345C'
        dse.carLicense = null
        assertEquals dse.modifications.size(), 0
        assertEquals dse.entry.getAttributes().size(), 28
    }

    /**
     * Test propertyMissing with deleting values.
     */
    void testPropertyMissingRemoveValue() {
        assertEquals dse.mail, 'Julie.Nguyen@someu.edu'
        dse.mail = ''
        assertNull dse.mail
        assertEquals dse.modifications.size(), 1
        assertEquals dse.modifications[0].getModificationType().getName(), 'DELETE'

        dse.mail = 'some.mail@someu.edu'
        assertEquals dse.modifications.size(), 2
        assertEquals dse.mail, 'some.mail@someu.edu'
    }
    
    /**
     * Test replace instead of delete/add.
     */
    void testReplaceInsteadOfDeleteAdd() {
        assertEquals dse.mail, 'Julie.Nguyen@someu.edu'
        dse.mail = 'some.mail@someu.edu'
        dse.updateModifications(false, true)
        assertEquals dse.modifications.size(), 1
        assertEquals dse.modifications[0].getModificationType().getName(), 'REPLACE'
    }

    /**
     * Test discard.
     */
    void testDiscard() {
        dse.mail = null
        dse.carLicense = 'B12345C'
        assertNull dse.mail
        assertEquals dse.carLicense, 'B12345C'
        dse.discard()
        assertEquals dse.mail, 'Julie.Nguyen@someu.edu'
        assertNull dse.carLicense
    }
}
