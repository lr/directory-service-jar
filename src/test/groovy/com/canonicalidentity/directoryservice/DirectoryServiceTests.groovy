package com.canonicalidentity.directoryservice

import groovy.util.GroovyTestCase

import com.canonicalidentity.directoryservice.listener.InMemoryDirectoryServer
import com.unboundid.ldap.sdk.Filter as LDAPFilter

class DirectoryServiceTests extends GroovyTestCase {

    /*
     * Items needed for pulling in the configuration for both the
     * InMemoryServer and the DirectoryService.
     */
    def dsConfigFile = "conf/Config.groovy"
    def inMemServerConfigFile = "conf/InMemoryServerConfig.groovy"
    def inMemServerConfig = new ConfigSlurper().parse(new File(inMemServerConfigFile).toURL())
    
    /* The InMemoryServer instance */
    def inMemServer
    
    /* The "AD" InMemoryServer instanceof */
    def adInMemServer 
    
    /* The DirectoryService instance */
    def directoryService = new DirectoryService(dsConfigFile)
    
    /* Used for comparison. */
    def peopleBaseDN = 'ou=people,dc=someu,dc=edu'

    /* Used for testing the fake AD server. */
    def accountsBaseDN = 'ou=accounts,dc=someu,dc=edu'

    /* Used for testing the Economics accounts branch. */
    def accountsEconomicsBaseDn = 'ou=Economics,ou=accounts,dc=someu,dc=edu'
    
    protected void setUp() {
        inMemServer = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            inMemServerConfig.directoryservice.sources.directory,
            "src/test/ldif/schema/directory-schema.ldif",
            "src/test/ldif/directory.ldif"
        )
        
        adInMemServer = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            inMemServerConfig.directoryservice.sources.ad,
            "src/test/ldif/schema/ad-schema.ldif",
            "src/test/ldif/accounts.ldif"
        )
    }
    
    protected void tearDown() {
        inMemServer?.shutDown()
        adInMemServer?.shutDown()
    }
    
    /*
     * Test the getting the source from the provided base.
     */
    void testSource() {
        def source = directoryService.sourceFromBase(peopleBaseDN)

        assertEquals source.address, 'localhost , localhost'
        assertEquals source.port, ' 11389 ,33389'
        assertFalse  source.useSSL
        assertTrue   source.trustSSLCert
        assertEquals source.bindDN, 'cn=Directory Manager'
        assertEquals source.bindPassword, 'password'

        source = directoryService.sourceFromBase(accountsBaseDN)
        assertEquals source.address, 'localhost'
        assertEquals source.port, '33268'
        assertFalse  source.useSSL
        assertTrue   source.trustSSLCert
        assertEquals source.bindDN, 'cn=AD Manager'
        assertEquals source.bindPassword, 'password'
    }
    
    void testFindPeople() {
        def people = directoryService.findPeopleWhere("sn=smith")
        assert people.size() == 5
        assert people[0].givenName == 'John'
    }
    
    /**
     * Test andFilterFromArgs to make sure it outputs an LDAPFilter.
     */
    void testAndFilterFromArgs() {
        def args = ['sn':'nguyen']
        def filter = directoryService.andFilterFromArgs(args)
        assertNotNull filter
        assert filter instanceof com.unboundid.ldap.sdk.Filter
        assertEquals filter.toString(), '(&(sn=nguyen))'

        args = ['sn':'smith', 'givenName':'sally']
        filter = directoryService.andFilterFromArgs(args)
        assertNotNull filter
        assert filter instanceof com.unboundid.ldap.sdk.Filter
        assertEquals filter.toString(), '(&(sn=smith)(givenName=sally))'

    }
    
    /**
     * Test the base find method itself. This will return the actual
     * entry itself because it is "find", and not "findAll".
     */
    void testFind() {
        def args = ['sn':'evans']
        def result = directoryService.findEntry(peopleBaseDN, args)

        assertNotNull result
        assert result instanceof com.canonicalidentity.directoryservice.DirectoryServiceEntry
        assert result.entry instanceof com.unboundid.ldap.sdk.Entry

        // Test the UnboundID SearchResultEntry
        assertEquals result.getAttributeValue("sn"), 'Evans'
        // Test the LdapServiceEntry
        assertEquals result.sn, 'Evans'

    }
    
    /**
     * The the base find using an AD Economics OU. There are a lot
     * of Jills in AD, but only one in econ.
     */
    void testFindADOU() {
        def args = ['givenName':'jill']
        def result = directoryService.findEntry(accountsEconomicsBaseDn, args)

        assertNotNull result
        assert result instanceof com.canonicalidentity.directoryservice.DirectoryServiceEntry
        assert result.entry instanceof com.unboundid.ldap.sdk.Entry

        // Test the UnboundID SearchResultEntry
        assertEquals result.getAttributeValue("sn"), 'Kannel'
        // Test the LdapServiceEntry
        assertEquals result.sn, 'Kannel'

    }
    
    void testModifyPerson() {
        def john = directoryService.findPersonWhere(givenName: 'John', sn: 'Smith')
        assert john != null
        john.givenName = 'Billy'
        directoryService.save(john)
        
        def billy = directoryService.findPersonWhere(givenName: 'Billy', sn: 'Smith')
        assert billy != null
        assert billy.givenName == 'Billy'
    }
    
    void testModifyPersonChangeAttributeValueCase() {
        def john = directoryService.findPersonWhere(givenName: 'John', sn: 'Smith')
        assert john != null
        john.givenName = 'john'
        directoryService.save(john)
        
        def johnAgain = directoryService.findPersonWhere(givenName: 'John', sn: 'Smith')
        assert johnAgain != null
        assert johnAgain.givenName == 'john'
    }
    
}
