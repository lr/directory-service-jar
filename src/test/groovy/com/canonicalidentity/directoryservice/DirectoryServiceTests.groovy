package com.canonicalidentity.directoryservice

import groovy.test.GroovyTestCase

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

    /* The "AD" InMemoryServer instanceof */
    def adInMemServerWithPool

    /* The DirectoryService instance */
    def directoryService = new DirectoryService(dsConfigFile)

    /* Used for comparison. */
    def peopleBaseDN = 'ou=people,dc=someu,dc=edu'

    /* Used for testing the fake AD server. */
    def accountsBaseDN = 'ou=accounts,dc=someu,dc=edu'

    /* Used for testing the Economics accounts branch. */
    def accountsEconomicsBaseDN = 'ou=Economics,ou=accounts,dc=someu,dc=edu'

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

        adInMemServerWithPool = new InMemoryDirectoryServer(
            "dc=someu,dc=edu",
            inMemServerConfig.directoryservice.sources.adWithPool,
            "src/test/ldif/schema/ad-schema.ldif",
            "src/test/ldif/accounts.ldif"
        )
    }

    protected void tearDown() {
        inMemServer?.shutDown()
        adInMemServer?.shutDown()
        adInMemServerWithPool?.shutDown()
    }

    void testPreventConfigMods() {
        def ds = new DirectoryService(dsConfigFile, false)
        assertNotNull ds.config.directoryservice.sources
        assertNotNull ds.config.directoryservice.sources['directory']
        ds.config.directoryservice.sources['directory']['newKey'] = 'newValue'

        assertEquals ds.config.directoryservice.sources['directory']['newKey'], 'newValue'

        ds.preventConfigMods()
        try {
            ds.config.directoryservice.sources['directory']['useSSL'] = true
        }
        catch (java.lang.UnsupportedOperationException e) {
            assertEquals e.class, java.lang.UnsupportedOperationException
        }
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
     * Test the base findAll method itself.
     */
    void testFindAll() {
        def args = ['sn':'James']
        def results = directoryService.findEntries(peopleBaseDN, args)

        assertNotNull results
        assert results[0] instanceof com.canonicalidentity.directoryservice.DirectoryServiceEntry
        assert results[0].entry instanceof com.unboundid.ldap.sdk.Entry
        assertEquals results[0].getAttributeValue("sn"), 'James'
        assertEquals results.size(), 4
    }

    /**
     * The the base find using an AD Economics OU. There are a lot
     * of Jills in AD, but only one in econ.
     */
    void testFindADOU() {
        def args = ['givenName':'jill']
        def result = directoryService.findEntry(accountsEconomicsBaseDN, args)

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
        assertNotNull john
        john.givenName = 'Billy'
        directoryService.save(john)

        def billy = directoryService.findPersonWhere(givenName: 'Billy', sn: 'Smith')
        assertNotNull billy != null
        assertEquals billy.givenName, 'Billy'
    }

    void testModifyPersonChangeAttributeValueCase() {
        def john = directoryService.findPersonWhere(givenName: 'John', sn: 'Smith')
        assertNotNull john
        assertEquals john.givenName, 'John'
        john.givenName = 'john'
        directoryService.save(john)

        def johnAgain = directoryService.findPersonWhere(givenName: 'John', sn: 'Smith')
        assertNotNull johnAgain
        assertEquals johnAgain.givenName, 'john'
    }

    /**
     * The the base findAll using an AD Economics OU. There are a lot
     * of Jills in AD, but only one in econ.
     */
    void testFindAllADOU() {
        def args = ['givenName':'jill']
        def results = directoryService.findEntries(accountsEconomicsBaseDN, args)

        assertNotNull results
        assert results[0] instanceof com.canonicalidentity.directoryservice.DirectoryServiceEntry
        assert results[0].entry instanceof com.unboundid.ldap.sdk.Entry

        assertEquals results.size(), 1

        // Test the UnboundID SearchResultEntry
        assertEquals results[0].getAttributeValue("sn"), 'Kannel'
        // Test the LdapServiceEntry
        assertEquals results[0].sn, 'Kannel'

    }

    /**
     * Test findPersonWhere with a single argument.
     */
    void testFindPersonWhereSingleArg() {
        def person = directoryService.findPersonWhere('uid':'1')
        assertNotNull person
        assertEquals person.getAttributeValue("uid"), '1'
        assertEquals person.getAttributeValue("sn"), 'Williams'
    }

    /**
     * Test findPersonWhere with multiple arguments.
     */
    void testFindPersonWhereMultipleArgs() {
        def person = directoryService.findPersonWhere('givenName':'roland', 'sn':'conner')
        assertNotNull person
        assertEquals person.getAttributeValue("uid"), '11'
        assertEquals person.getAttributeValue("givenName"), 'Roland'
        assertEquals person.getAttributeValue("sn"), 'Conner'
    }

    /**
     * Test findAllPeopleWhere with a single argument.
     */
    void testFindPeopleWhereSingleArg() {
        def people = directoryService.findPeopleWhere('sn':'williams')
        assertNotNull people
        assertEquals people.size(), 4
        assertEquals people[0].getAttributeValue("sn"), 'Williams'
    }

    /**
     * Test findPeopleWhere with size limit
     */
    void testFindPeopleWhereWithSizeLimit() {
        def filter = directoryService.createFilter('(&(sn=wa*))')
        assert filter instanceof LDAPFilter
        def people = directoryService.findPeopleWhere(filter, [sizeLimit:10])
        assertNotNull people
        assertEquals 10, people.size()
    }

    /**
     * Test findAllPeopleWhere with multiple arguments.
     */
    void testFindPeopleWhereMultipleArgs() {
        def people = directoryService.findPeopleWhere('sn':'williams', 'l':'berkeley')
        assertNotNull people
        assertEquals people.size(), 2
        assertEquals people[0].getAttributeValue("l"), 'Berkeley'
    }

    void testFindPeopleWhereMultipleArgsWithSort() {
        def people = directoryService.findPeopleWhere(sn:'williams', st:'ca', [sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 4
        assertEquals people[0].cn, 'Williams, Jim'
        assertEquals people[1].cn, 'Williams, Matthew'
        assertEquals people[2].cn, 'Williams, Russ'
        assertEquals people[3].cn, 'Williams, Sandy'

        people = directoryService.findPeopleWhere(sn:'williams', l:'berkeley', [sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 2
        assertEquals people[0].cn, 'Williams, Jim'
        assertEquals people[1].cn, 'Williams, Matthew'
    }

    /**
     * Test findAllPeopleWhere with multiple arguments, size limit and sort
     */
    void testFindPeopleWhereMultipleArgsWithSizeLimitAndSort() {
        def people = directoryService.findPeopleWhere(sn:'williams', st:'ca', [sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 4
        assertEquals people[0].cn, 'Williams, Jim'
        assertEquals people[1].cn, 'Williams, Matthew'
        assertEquals people[2].cn, 'Williams, Russ'
        assertEquals people[3].cn, 'Williams, Sandy'

        people = directoryService.findPeopleWhere(sn:'williams', st:'ca', [sizeLimit:3, sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 3
        assertEquals people[0].cn, 'Williams, Jim'
        assertEquals people[1].cn, 'Williams, Matthew'
        assertEquals people[2].cn, 'Williams, Russ'

        people = directoryService.findPeopleWhere(sn:'williams', l:'berkeley', [sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 2
        assertEquals people[0].cn, 'Williams, Jim'
        assertEquals people[1].cn, 'Williams, Matthew'
    }

    /**
     * Test the createFilter method.
     */
    void testCreateFilter() {
        def filter = directoryService.createFilter('(&(sn=do*))')
        assert filter instanceof LDAPFilter

        filter = directoryService.createFilter('(&(sn=doe)(departmentNumber=12345)(|(sn=rockwell)))')
        assert filter instanceof LDAPFilter
    }

    /**
     * Test the findAllUsingFilter method.
     */
    void testFindAllUsingFilter() {
        def filter = directoryService.createFilter('(&(sn=wa*))')
        assert filter instanceof LDAPFilter

        def people = directoryService.findEntriesUsingFilter(peopleBaseDN, filter)
        assertNotNull people
        assertEquals 16, people.size()
    }

    void testFindAllUsingFilterWithSizeLimit() {
        def filter = directoryService.createFilter('(&(sn=wa*))')
        assert filter instanceof LDAPFilter

        def people = directoryService.findEntriesUsingFilter(peopleBaseDN, filter, [sizeLimit:10])
        assertNotNull people
        assertEquals 10, people.size()
    }

    void testFindAllUsingFilterWithTimeLimit() {
        def filter = directoryService.createFilter('(&(sn=wa*))')
        assert filter instanceof LDAPFilter

        def people = directoryService.findEntriesUsingFilter(peopleBaseDN, filter, [timeLimit:10])
        assertNotNull people
        assertEquals 16, people.size()
    }

    /**
     * Test the findAllPeopleWhere method when supplying a filter.
     */
    void testFindAllPeopleWhereUsingFilter() {
        def filter = directoryService.createFilter('(&(sn=wa*))')
        def people = directoryService.findPeopleWhere(filter)
        assertNotNull people
        assertEquals people.size(), 16

        filter = directoryService.createFilter('(|(sn=walters)(sn=williams))')
        people = directoryService.findPeopleWhere(filter)
        assertNotNull people
        assertEquals people.size(), 8
    }

    /**
     * Test that we can find a single person by using a filter.
     */
    void testFindOnePersonUsingFilter() {
        def filter = directoryService.createFilter('(&(givenName=Catherine)(sn=Smith)(|(uid=288)))')
        def person = directoryService.findPersonWhere(filter)
        assertNotNull person
        assertEquals person.cn, 'Smith, Catherine'
        assertEquals person.givenName, 'Catherine'
        assertEquals person.sn, 'Smith'
    }

    /**
     * Test that we can find a single person by using a filter.
     */
    void testFindOnePersonUsingString() {
        def filter = '(&(givenName=Catherine)(sn=Smith)(|(uid=288)))'
        def person = directoryService.findPersonWhere(filter)
        assertNotNull person
        assertEquals person.cn, 'Smith, Catherine'
        assertEquals person.givenName, 'Catherine'
        assertEquals person.sn, 'Smith'
    }

    /**
     * Test the findAllPeopleWhere method when supplying a filter and a sort
     * attribute.
     */
    void testFindAllPeopleWhereUsingFilterWithSort() {
        def filter = directoryService.createFilter('(&(sn=smith))')
        def people = directoryService.findPeopleWhere(filter, [sort:'cn'])
        assertNotNull people
        assertEquals people.size(), 5
        assertEquals people[0].cn, 'Smith, Catherine'
        assertEquals people[4].cn, 'Smith, William'
    }

    /**
     * Test of specified attributes, which are "*" and "createTimestamp".
     */
    void testFindWithReturnAttributes() {
        def person = directoryService.findPersonWhere(uid:'3')
        assertEquals person.displayName, 'Mabel Rockland'
        assertNotNull person.createTimestamp
    }

    /**
     * Test of sub entries.
     */
    void testFindSubentries() {
        def entries = directoryService.findSubentriesWhere(
            'uid=0,ou=people,dc=someu,dc=edu',
            [objectClass:'room'])
        assertNotNull entries
        assertEquals entries.size(), 4

        entries = directoryService.findSubentriesWhere(
            'uid=0,ou=people,dc=someu,dc=edu',
            [objectClass:'room'],
            [sort:'cn'])
        assertNotNull entries
        assertEquals entries.size(), 4
        assertEquals entries[0].cn, 'Cafe'
        assertEquals entries[1].cn, 'Lab'
        assertEquals entries[2].cn, 'Main Office'
        assertEquals entries[3].cn, 'Starlight Room'

    }

    /**
     * Test of sub entries using a filter instead of a Map.
     */
    void testFindSubentriesUsingFilter() {
        def filter = directoryService.createFilter('(&(objectClass=room))')
        def entries = directoryService.findSubentriesWhere(
            'uid=0,ou=people,dc=someu,dc=edu',
            filter,
            [sort:'cn'])
        assertNotNull entries
        assertEquals entries.size(), 4
        assertEquals entries[0].cn, 'Cafe'
        assertEquals entries[1].cn, 'Lab'
        assertEquals entries[2].cn, 'Main Office'
        assertEquals entries[3].cn, 'Starlight Room'
    }

    /**
     * Test that we are only returning the cn, sn, and creatorsName because
     * we are specifying only those attributes (when searching using a Filter)
     */
    void testOnlySnAndCnReturnedAsArgumentsUsingFilter() {
        def filter = directoryService.createFilter('(&(uid=3))')
        def person = directoryService.findPersonWhere(filter, [attrs:['sn', 'cn', 'creatorsName']])
        assertNotNull person
        assertEquals person.sn, 'Rockland'
        assertEquals person.cn, 'Rockland, Mabel'
        assertEquals person.creatorsName, 'cn=Internal Root User'
        assertNull person.givenName
        assertNull person.employeeNumber
        assertNull person.mail
    }

    /**
     * Test that we are only returning sn, and creatorsName because
     * we are specifying only those attributes (when searching using a String)
     */
    void testOnlySnIsReturnedAsSpecifingJustTwoRecordsAsArgumentsUsingStringFilter() {
        def people = directoryService.findPeopleWhere('(&(sn=smith))', [attrs:['sn'], sizeLimit:2])
        assertNotNull people
        def person = people[0]
        assertEquals people.size(), 2
        assertEquals person.sn, 'Smith'
        assertNull person.cn
        assertNull person.givenName
        assertNull person.employeeNumber
        assertNull person.mail
    }

    /**
     * Test that we are only returning sn, and creatorsName because
     * we are specifying only those attributes (when searching using a String)
     */
    void testOnlyCNIsReturnedForTwoRecordsSortedByCN() {
        def people = directoryService.findPeopleWhere('(&(sn=smith))', [attrs:['cn'], sizeLimit:2, sort:'cn'])
        assertNotNull people
        def person = people[0]
        assertEquals people.size(), 2
        assertEquals person.cn, 'Smith, Catherine'
        assertNull person.sn
        assertNull person.givenName
        assertNull person.employeeNumber
        assertNull person.mail
        assertEquals people[1].cn, 'Smith, John'
    }

    /**
     * Test get, which operates on the RDN attribute of the dit object
     * specified.
     */
    void testGetUsingAttr() {
       def person = directoryService.getPerson('1')
       assertNotNull person
       assertEquals person.uid, '1'

       def account = directoryService.getAccount('Lavers, Matthew')
       assertNotNull account
       assertEquals account.uid, '139'
    }

    void testGetUsingDN() {
        def person = directoryService.getPerson('uid=1,ou=people,dc=someu,dc=edu')
        assertNotNull person
        assertEquals person.uid, '1'
        assertEquals person.displayName, 'Jim Williams'

        def account = directoryService.getAccount('cn=Evans\\, Sally,ou=Aerospace Engineering,ou=accounts,dc=someu,dc=edu')
        assertNotNull account
        assertEquals account.uid, '2'
        assertEquals account.displayName, 'Sally Evans'
    }

    /**
     * Test get, which operates on the RDN attribute of the dit object
     * specified, but only return the specified attributes.
     */
    void testGetWithSpecifiedAttrs() {
       def person = directoryService.getPerson('1', [attrs:['uid']])
       assertNotNull person
       assertEquals person.uid, '1'
       assertNull person.givenName
       assertNull person.sn

       def account = directoryService.getAccount('Lavers, Matthew', [attrs:['uid']])
       assertNotNull account
       assertEquals account.uid, '139'
       assertNull person.givenName
       assertNull person.sn
    }

    /**
     * Tests searchUsingFilter using a variety of the features.
     */
    void testSearchUsingFilter() {
        def baseDN = 'ou=people,dc=someu,dc=edu'
        def filter = '(&(sn=smith))'
        def people = directoryService.searchUsingFilter(baseDN, filter)
        assertEquals people.size(), 5

        people = directoryService.searchUsingFilter(baseDN, filter, [sort:'cn'])
        assertEquals people.size(), 5
        assertEquals people[0].getAttributeValue('givenName'), 'Catherine'
        assertNotNull people[0].getAttributeValue('mail')

        people = directoryService.searchUsingFilter(baseDN, filter, [sort:'cn', attrs:['givenName']])
        assertEquals people.size(), 5
        assertEquals people[0].getAttributeValue('givenName'), 'Catherine'
        assertNull people[0].getAttributeValue('mail')
        assertNull people[1].getAttributeValue('mail')

        // Supplying attrs at the end of the method.
        people = directoryService.searchUsingFilter(baseDN, filter, [sort:'cn'], 'sn', 'cn')
        assertEquals people.size(), 5
        assertEquals people[0].getAttributeValue('sn'), 'Smith'
        assertEquals people[0].getAttributeValue('cn'), 'Smith, Catherine'
        assertEquals people[1].getAttributeValue('sn'), 'Smith'
        assertEquals people[1].getAttributeValue('cn'), 'Smith, John'
        assertNull people[0].getAttributeValue('givenName')
        assertNull people[0].getAttributeValue('mail')
        assertNull people[1].getAttributeValue('givenName')
        assertNull people[1].getAttributeValue('mail')

        // Supplying attrs in the map overrides the attrs at the end of the method.
        people = directoryService.searchUsingFilter(baseDN, filter, [sort:'cn', attrs:['sn', 'cn']], 'givenName', 'mail')
        assertEquals people.size(), 5
        assertEquals people[0].getAttributeValue('sn'), 'Smith'
        assertEquals people[0].getAttributeValue('cn'), 'Smith, Catherine'
        assertEquals people[1].getAttributeValue('sn'), 'Smith'
        assertEquals people[1].getAttributeValue('cn'), 'Smith, John'
        assertNull people[0].getAttributeValue('givenName')
        assertNull people[0].getAttributeValue('mail')
        assertNull people[1].getAttributeValue('givenName')
        assertNull people[1].getAttributeValue('mail')
    }

    /**
     * Tests paged searching. We have to resort to some trickery to see if we
     * can test the size. To ensure we are actually doing a paged search, we
     * have enabled warn logging and can now verify the number of paged searches
     * in the log.
     */
    void testPagedSearch() {
        def people = directoryService.findPeopleWhere("(&(uid=*))", [pagedSearch: true, pageSize: 10])
        assertNotNull people
        assertEquals people.size(), 385

        people = directoryService.findPeopleWhere("(&(uid=*))",
            [sort: 'cn', attrs:['givenName', 'sn'], pagedSearch: true, pageSize: 10])
        assertNotNull people
        assertEquals people.size(), 385

        // If the sizeLimit is 10 or more, we will always get the entire amount
        // of results.
        people = directoryService.findPeopleWhere("(&(uid=*))",
            [sort: 'cn', attrs:['givenName', 'sn'], pagedSearch: true, pageSize: 10, sizeLimit: 10])
        assertNotNull people
        assertEquals people.size(), 385

        people = directoryService.findPeopleWhere("(&(uid=*))",
            [sort: 'cn', attrs:['givenName', 'sn'], pagedSearch: true, pageSize: 10, sizeLimit: 15])
        assertNotNull people
        assertEquals people.size(), 385

        // Each page size is 10, and we limit the number of results to 9, so
        // that means, desipte pagings, we are only going to get 9.
        people = directoryService.findPeopleWhere("(&(uid=*))",
            [sort: 'cn', attrs:['givenName', 'sn'], pagedSearch: true, pageSize: 10, sizeLimit: 9])
        assertNotNull people
        assertEquals people.size(), 9
    }

    /**
     * Test save().
     */
    void testSave() {
        def person = directoryService.getPerson('2')
        assertEquals person.sn, 'Evans'
        assertEquals person.cnValues().size(), 2
        person.sn = 'Evans-Peters'
        person.cn = ['Evans-Peters, Sally', 'Sally Evans-Peters', 'Sally Evans', 'Sally Peters']
        person.displayName = 'Sally Evans-Peters'
        person.mail = ''
        assertTrue person.isDirty()
        directoryService.save(person)

        // Now test that the original object got cleaned up.
        assertFalse person.isDirty()
        person.mail = 'testing@someu.edu'
        person.discard() // This will test that searchResultEntry got updated
        assertEquals person.sn, 'Evans-Peters'

        // Now fetch the person from the directory again to check that the
        // change was actually saved.
        def person2 = directoryService.getPerson('2')
        assertEquals person2.sn, 'Evans-Peters'
        assertEquals person2.cnValues().size(), 4
        assertEquals person2.displayName, 'Sally Evans-Peters'
        // Now let's really make sure this is the same person.
        assertEquals person2.initials, 'SDE'
        assertEquals person2.telephoneNumber, '+1 022 028 9350'
    }

    /**
     * Test save() with no mods. Should not throw an exception.
     */
    void testSaveWithNoMods() {
        def person = directoryService.getPerson('2')
        directoryService.save(person)

        person.sn = 'Something New'
        assertTrue person.isDirty()
        person.sn = 'Evans'
        assertTrue person.isDirty()
        directoryService.save(person)
        assertFalse person.isDirty()
    }

    /**
     * Test deleting an object using the connection.
     */
    void testDeleteUsingConnection() {
        def person = directoryService.getPerson('2')
        assertNotNull person
        directoryService.connection(peopleBaseDN)?.delete(person.dn)

        person = directoryService.getPerson('2')
        assertNull person
    }

    /**
     * Test getting attribute as objects (all).
     */
    void testAttrAsObjectsAll() {
        def group = directoryService.getGroup('some group')
        assertNotNull group

        def owners = directoryService.attrAsObjects(group, 'owner')
        assertEquals owners.size(), 3
    }

    /**
     * Test getting attribute as objects (groups).
     */
    void testAttrAsObjectsGroupsOnly() {
        def group = directoryService.getGroup('some group')
        assertNotNull group

        def owners = directoryService.attrAsObjects(group, 'owner', 'group')
        assertEquals owners.size(), 2
    }

    /**
     * Test getting attribute as objects (people).
     */
    void testAttrAsObjectsPeopleOnly() {
        def group = directoryService.getGroup('some group')
        assertNotNull group

        def owners = directoryService.attrAsObjects(group, 'owner', 'people')
        assertEquals owners.size(), 1
        assertEquals owners[0].uid, '6'
    }

    /**
     * Test getting attribute as objects (people) with limited attrs.
     */
    void testAttrAsObjectsPeopleOnlyLimitedAttrs() {
        def group = directoryService.getGroup('some group')
        assertNotNull group

        def owners = directoryService.attrAsObjects(group, 'owner', 'people', ['sn', 'mail'])
        assertEquals owners.size(), 1
        assertNull owners[0].uid
        assertNull owners[0].givenName
        assertEquals owners[0].sn, 'Nguyen'
        assertEquals owners[0].mail, 'Julie.Nguyen@someu.edu'
    }

    /**
     * Test snapshotting
     */
    void testSnapshot() {
        def snapshot = inMemServer.createSnapshot()
        def person = directoryService.getPerson('1')
        assertNotNull person

        directoryService.connection(peopleBaseDN)?.delete(person.dn)  
        person = directoryService.getPerson('1')
        assertNull person

        inMemServer.restoreSnapshot(snapshot)
        person = directoryService.getPerson('1')
        assertNotNull person
    }

    /**
     * Test the connections() map
     */
    void testConnections() {
        def people   = directoryService.findPeopleWhere(objectClass: 'person')
        def accounts = directoryService.findAccountsWhere(objectClass: 'person')

        assertEquals people.size(), 385
        assertEquals accounts.size(), 351
        // should be 1 because the default accounts does not use a connection pool
        assertEquals directoryService.connections().keySet().size(), 1

        def accountsFromPool = directoryService.findAccountsFromPoolWhere(objectClass: 'person')

        // now we search using the AD with the connection pool, which would keep
        // the connection open, so now we should have two conns returned
        assertEquals accountsFromPool.size(), 351
        assertEquals directoryService.connections().keySet().size(), 2
    }

    /**
     * The same object can be edited and saved repeatedly: after a save, the
     * next edit is diffed against what was saved, not against itself.
     */
    void testSaveTwiceInARow() {
        def person = directoryService.getPerson('2')
        person.sn = 'Once'
        assertTrue directoryService.save(person)
        person.sn = 'Twice'
        assertTrue person.isDirty('sn')
        assertFalse person.modifications.isEmpty()
        assertTrue directoryService.save(person)
        assertEquals 'Twice', directoryService.getPerson('2').sn
    }

    /**
     * A save the server rejects returns false and records the error, rather
     * than throwing.
     */
    void testFailedSaveReturnsFalse() {
        def person = directoryService.getPerson('2')
        person.someAttributeThatDoesNotExist = 'value'
        assertFalse directoryService.save(person)
        assertNotNull person.errors['save']
    }

    /**
     * save() writes a change record holding the target, type, LDIF of the
     * change, and the provided reason and modifier.
     */
    void testSaveWritesChangeRecord() {
        def person = directoryService.getPerson('2')
        person.sn = 'Evans-Peters'
        assertTrue directoryService.save(person,
            [reason: 'Name change from HR', modifier: 'hr-sync'])

        def records = directoryService.changesFor(person.dn)
        assertEquals 1, records.size()
        def record = records[0]
        assertEquals person.dn, record.ciChangeTarget
        assertEquals 'modify', record.ciChangeType
        assertEquals 'Name change from HR', record.ciModifyReason
        assertEquals 'hr-sync', record.ciModifiersName
        assertNotNull record.ciChangeTime

        def ldif = new String(record.entry.getAttributeValueBytes('ciChanges'), 'UTF-8')
        assertTrue ldif.contains("dn: ${person.dn}")
        assertTrue ldif.contains('changetype: modify')
        assertTrue ldif.contains('sn: Evans-Peters')
    }

    /**
     * Without a modifier, the change record uses the changelog source's bind DN.
     */
    void testChangeRecordDefaultsModifierToBindDN() {
        def person = directoryService.getPerson('2')
        person.displayName = 'Sally E.'
        assertTrue directoryService.save(person)

        def record = directoryService.changesFor(person.dn)[0]
        assertEquals 'cn=Directory Manager', record.ciModifiersName
        assertNull record.ciModifyReason
    }

    /**
     * Members added to or removed from a group become subjects of the change
     * record, so a person's lost (or gained) memberships can be found by
     * searching for that person.
     */
    void testGroupMembershipChangeIsFoundByMember() {
        def memberDN = 'uid=6,ou=people,dc=someu,dc=edu'
        def group = directoryService.getGroup('cn=some group,ou=groups,dc=someu,dc=edu')
        def members = group.memberValues() as List
        assertTrue members.contains(memberDN)

        group.member = members - memberDN
        assertTrue directoryService.save(group,
            [reason: 'No longer matches the dynamic rule for some group'])

        def records = directoryService.changesFor(memberDN)
        assertEquals 1, records.size()
        assertEquals group.dn, records[0].ciChangeTarget
        assertEquals 'No longer matches the dynamic rule for some group',
            records[0].ciModifyReason
        // The group itself is a subject too.
        assertEquals 1, directoryService.changesFor(group.dn).size()
    }

    /**
     * changesFor() honors since and until, and returns records oldest first.
     */
    void testChangesForTimeRangeAndOrder() {
        def person = directoryService.getPerson('2')
        person.sn = 'First'
        directoryService.save(person)
        person.sn = 'Second'
        directoryService.save(person)

        def all = directoryService.changesFor(person.dn)
        assertEquals 2, all.size()
        assertTrue new String(all[0].entry.getAttributeValueBytes('ciChanges'), 'UTF-8').contains('sn: First')
        assertTrue new String(all[1].entry.getAttributeValueBytes('ciChanges'), 'UTF-8').contains('sn: Second')

        def tomorrow = new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
        def yesterday = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        assertEquals 0, directoryService.changesFor(person.dn, tomorrow).size()
        assertEquals 2, directoryService.changesFor(person.dn, yesterday).size()
        assertEquals 0, directoryService.changesFor(person.dn, null, yesterday).size()
    }

    /**
     * purgeChangeRecords() deletes records older than the retention period
     * and keeps recent ones.
     */
    void testPurgeChangeRecords() {
        def person = directoryService.getPerson('2')
        person.sn = 'Recent'
        directoryService.save(person)

        // An old record, written directly with a change time two years ago.
        def oldTime = '20241123000000.0Z'
        directoryService.connection('ou=changelog,dc=someu,dc=edu').add(
            "dn: cn=${oldTime}-old,ou=changelog,dc=someu,dc=edu",
            'objectClass: top',
            'objectClass: ciChangeRecord',
            "cn: ${oldTime}-old",
            "ciChangeTarget: ${person.dn}",
            "ciChangeTime: ${oldTime}",
            'ciChangeType: modify',
            "ciChangeSubject: ${person.dn}")
        assertEquals 2, directoryService.changesFor(person.dn).size()

        assertEquals 1, directoryService.purgeChangeRecords()
        def remaining = directoryService.changesFor(person.dn)
        assertEquals 1, remaining.size()
        assertTrue new String(remaining[0].entry.getAttributeValueBytes('ciChanges'), 'UTF-8').contains('sn: Recent')
    }

    /**
     * Recording is best-effort: if the change record cannot be written, the
     * save itself still succeeds.
     */
    void testSaveSucceedsWhenChangeRecordFails() {
        def ds = new DirectoryService(dsConfigFile, false)
        // A changes dit item whose branch does not exist in the directory.
        ds.config.directoryservice.dit['ou=missing,ou=people,dc=someu,dc=edu'] = [
            singular: 'missingChangeRecord', plural: 'missingChangeRecords',
            rdnAttribute: 'cn', source: 'directory'
        ]
        ds.config.directoryservice.sources.directory.changesDITItem = 'missingChangeRecord'

        def person = ds.getPerson('2')
        person.sn = 'Still Saved'
        assertTrue ds.save(person)
        assertEquals 'Still Saved', ds.getPerson('2').sn
        assertFalse ds.recordChange('modify', person.dn, [])
    }

    /**
     * Saves to a source without trackChanges are not recorded.
     */
    void testUntrackedSourceRecordsNothing() {
        def account = directoryService.findAccountWhere(sAMAccountName: 'JSmith0')
        account.displayName = 'Johnny Smith'
        assertTrue directoryService.save(account)
        assertTrue directoryService.changesFor(account.dn).isEmpty()
    }

    /**
     * Sources can share one changes branch, so one search finds changes made
     * to any of them. The modifier defaults to the changed source's bind DN.
     */
    void testTrackedSourcesShareAChangesBranch() {
        def ds = new DirectoryService(dsConfigFile, false)
        ds.config.directoryservice.sources.ad.trackChanges = true
        ds.config.directoryservice.sources.ad.changesDITItem = 'changeRecord'

        def account = ds.findAccountWhere(sAMAccountName: 'JSmith0')
        account.displayName = 'Johnny Smith'
        assertTrue ds.save(account)

        def records = ds.changesFor(account.dn)
        assertEquals 1, records.size()
        assertEquals 'cn=AD Manager', records[0].ciModifiersName
        // Stored in the directory source's changelog branch.
        assertTrue records[0].dn.endsWith('ou=changelog,dc=someu,dc=edu')
    }

    /**
     * trackChanges with a missing or unknown changesDITItem records nothing,
     * and the save still succeeds.
     */
    void testTrackChangesWithoutAValidChangesDITItem() {
        def ds = new DirectoryService(dsConfigFile, false)
        ds.config.directoryservice.sources.ad.trackChanges = true

        def account = ds.findAccountWhere(sAMAccountName: 'JSmith0')
        account.displayName = 'No Item'
        assertTrue ds.save(account)
        assertTrue ds.changesFor(account.dn).isEmpty()

        ds.config.directoryservice.sources.ad.changesDITItem = 'noSuchItem'
        account.displayName = 'Unknown Item'
        assertTrue ds.save(account)
        assertTrue ds.changesFor(account.dn).isEmpty()
    }

    /**
     * A DirectoryService whose directory source records changes in a made-up,
     * non-ci schema: its own object class, RDN attribute and attribute names,
     * and no id, modifier or reason attributes at all.
     */
    private DirectoryService customSchemaService(Map changeAttributes) {
        def ds = new DirectoryService(dsConfigFile, false)
        ds.config.directoryservice.dit['ou=altchanges,dc=someu,dc=edu'] = [
            singular: 'altChange', plural: 'altChanges',
            rdnAttribute: 'testRecordId', source: 'directory',
            objectClass: ['top', 'testChangeEntry'],
            changeAttributes: changeAttributes
        ]
        ds.config.directoryservice.sources.directory.changesDITItem = 'altChange'
        return ds
    }

    /**
     * Change recording, searching and purging assume nothing about the ci
     * schema: they work with whatever the changes dit item configures.
     */
    void testChangeRecordsWithACustomSchema() {
        def ds = customSchemaService([
            target: 'testTarget', time: 'testWhen', type: 'testKind',
            changes: 'testDiff', subject: 'testAffected',
            id: '', modifier: '', reason: ''
        ])
        def memberDN = 'uid=6,ou=people,dc=someu,dc=edu'
        def group = ds.getGroup('cn=some group,ou=groups,dc=someu,dc=edu')
        group.member = (group.memberValues() as List) - memberDN
        assertTrue ds.save(group, [reason: 'Not recorded: no reason attribute'])

        def records = ds.changesFor(memberDN)
        assertEquals 1, records.size()
        def record = records[0]
        assertTrue record.dn.startsWith('testRecordId=')
        assertTrue record.dn.endsWith(',ou=altchanges,dc=someu,dc=edu')
        assertEquals group.dn, record.testTarget
        assertEquals 'modify', record.testKind
        assertNotNull record.testWhen
        assertTrue new String(record.entry.getAttributeValueBytes('testDiff'), 'UTF-8')
            .contains('changetype: modify')
        assertEquals(['testChangeEntry', 'top'] as Set,
            record.entry.getAttributeValues('objectClass') as Set)
        record.entry.attributes.each { assertFalse it.name.startsWith('ci') }

        // Purging uses the custom time attribute and object class too.
        def oldTime = '20241123000000.0Z'
        ds.connection('ou=altchanges,dc=someu,dc=edu').add(
            "dn: testRecordId=${oldTime}-old,ou=altchanges,dc=someu,dc=edu",
            'objectClass: top',
            'objectClass: testChangeEntry',
            "testRecordId: ${oldTime}-old",
            "testTarget: ${group.dn}",
            "testWhen: ${oldTime}",
            'testKind: modify',
            "testAffected: ${group.dn}")
        assertEquals 2, ds.changesFor(group.dn).size()
        assertEquals 1, ds.purgeChangeRecords()
        assertEquals 1, ds.changesFor(group.dn).size()
    }

    /**
     * Without a subject attribute, changesFor() matches on the target DN.
     */
    void testChangesForUsesTheTargetWithoutASubjectAttribute() {
        def ds = customSchemaService([
            target: 'testTarget', time: 'testWhen', type: 'testKind',
            changes: '', subject: '', id: '', modifier: '', reason: ''
        ])
        def person = ds.getPerson('2')
        person.sn = 'No Subjects'
        assertTrue ds.save(person)

        def records = ds.changesFor(person.dn)
        assertEquals 1, records.size()
        assertNull records[0].entry.getAttribute('testAffected')
        assertNull records[0].entry.getAttribute('testDiff')
    }

    /**
     * A changes dit item without names for the required target, time and
     * type attributes records nothing, and the save still succeeds.
     */
    void testChangeAttributesMissingARequiredName() {
        def ds = customSchemaService([
            target: 'testTarget', time: '', type: 'testKind'
        ])
        def person = ds.getPerson('2')
        person.sn = 'Not Recorded'
        assertTrue ds.save(person)
        assertTrue ds.changesFor(person.dn).isEmpty()
    }

    /**
     * Changes to change records themselves are not recorded.
     */
    void testChangesToChangeRecordsAreNotRecorded() {
        def person = directoryService.getPerson('2')
        person.sn = 'Recorded'
        directoryService.save(person)

        def record = directoryService.changesFor(person.dn)[0]
        record.ciModifyReason = 'Annotated after the fact'
        assertTrue directoryService.save(record)
        assertTrue directoryService.changesFor(record.dn).isEmpty()
        assertEquals 1, directoryService.changesFor(person.dn).size()
    }

    /**
     * With no changelog configured, nothing is recorded.
     */
    void testNoChangelogConfigured() {
        def ds = new DirectoryService(inMemServerConfigFile, false)
        assertFalse ds.recordChange('modify', 'uid=2,ou=people,dc=someu,dc=edu', [])
        assertTrue ds.changesFor('uid=2,ou=people,dc=someu,dc=edu').isEmpty()
        assertEquals 0, ds.purgeChangeRecords()
    }

}
