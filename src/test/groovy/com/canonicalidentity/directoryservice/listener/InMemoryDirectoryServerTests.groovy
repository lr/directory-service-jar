package com.canonicalidentity.directoryservice.listener

import com.canonicalidentity.directoryservice.DirectoryService
import com.canonicalidentity.directoryservice.DirectoryServiceEntry

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldif.LDIFReader

class InMemoryDirectoryServerTests extends GroovyTestCase {
    
    /*
     * Items needed for pulling in the configuration for the InMemoryServer.
     */
    def configFile = "conf/InMemoryServerConfig.groovy"
    def inMemServerConfig = new ConfigSlurper().parse(new File(configFile).toURL())
    
    /* Used for comparison. */
    def peopleBaseDN = 'ou=people,dc=someu,dc=edu'
    
    /**
     * Test that we can read from the sources map properly.
     */
    void testConfig() {
        assertEquals config.sources.directory.port, '33389'
    }
    
    /**
     * Test that the directory schema is parsed properly.
     */
    void testSchemaEntryFromLDIF() {
        def inMemServer = new InMemoryDirectoryServer()
        def schema = inMemServer.schemaEntryFromLDIF("src/test/ldif/schema/directory-schema.ldif")
        assertEquals schema.schemaEntry.DN, 'cn=schema'
    }
    
    /**
     * Test that the AD schema is parsed properly.
     */
    void testADSchemaFromLDIF() {
        def inMemServer = new InMemoryDirectoryServer()
        def schema = inMemServer.schemaEntryFromLDIF("src/test/ldif/schema/ad-schema.ldif")
        assertEquals schema.schemaEntry.DN, 'cn=schema'
    }
    
    /**
     * Test setting up of the server, listening on the provided port, and
     * then searching. Basically, if you can perform a search, the server
     * was set up properly.
     */
    void testListenAndSearch() {
        def server = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            config.sources.forListenTest,
            "src/test/ldif/schema/directory-schema.ldif",
            "src/test/ldif/directory.ldif"
        )

        def conn = new LDAPConnection(
            "localhost",
            Integer.parseInt(config.sources.forListenTest.port),
            config.sources.forListenTest.bindDN,
            config.sources.forListenTest.bindPassword
        )

        def result = conn.search(
            peopleBaseDN,
            SearchScope.SUB,
            "sn=Hampshire"
        )

        def entries = result.getSearchEntries()

        assertEquals entries.size(), 4

        server.shutDown()

    }
    
    /**
     * Test exporting of data from the server.
     */
    void testExport() {
        def server = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            config.sources.forListenTest,
            "src/test/ldif/schema/directory-schema.ldif",
            "src/test/ldif/directory.ldif"
        )

        def path = "/tmp/myexport.ldif"
        server.export(path)
        def reader = new LDIFReader(path)
        def entry = reader.readEntry()
        
        assertEquals entry.getDN(), 'dc=someu,dc=edu'
        
        server.shutDown()
    }
    
    
    private Map getConfig() {
        inMemServerConfig.directoryservice
    }
    
}
