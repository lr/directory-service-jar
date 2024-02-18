/*
 * Copyright 2024 Lucas Rockwell
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.canonicalidentity.directoryservice

import java.security.GeneralSecurityException
import java.util.Collections
import java.util.logging.Logger

import com.unboundid.asn1.ASN1OctetString
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.FailoverServerSet
import com.unboundid.ldap.sdk.Filter as LDAPFilter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPSearchException
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.RoundRobinServerSet
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResult
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.SimpleBindRequest
import com.unboundid.ldap.sdk.controls.ServerSideSortRequestControl
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl
import com.unboundid.ldap.sdk.controls.SortKey
import com.unboundid.util.ssl.SSLUtil
import com.unboundid.util.ssl.TrustAllTrustManager

/**
 * DirectoryService is a Grails Service that makes interacting with an LDAP
 * directory very easy. It is loosely based on GORM. However, because it is one
 * service class (as opposed to numerous concrete domain classes which is the
 * case with GORM) that interacts with a any part of a directory tree, and even
 * many different directory servers at once, you invoke an operation by
 * specifying the directory object to interact with as part of the method
 * signature.
 *
 * Given the following configuration:
 * <pre>
 *   directoryservice.sources = [
 *       'directory':[
 *           'address': 'server1,server2',
 *           'port': ' 636 ,636',
 *           'useSSL': true,
 *           'trustSSLCert': true,
 *           'followReferrals': true,
 *           'bindDN': 'cn=Some BindDN',
 *           'bindPassword': 'password'
 *       ],
 *       'ad':[
 *           'address': 'net1,net2',
 *           'port': '3269,3269',
 *           'useSSL': true,
 *           'trustSSLCert': true,
 *           'followReferrals': true,
 *           'bindDN': 'cn=AD BindDN',
 *           'bindPassword': 'password'
 *       ]
 *   ]
 *
 *   directoryservice.dit = [
 *       'ou=people,dc=someu,dc=edu':[
 *           'singular':'person',
 *           'plural':'people',
 *           'rdnAttribute':'uid',
 *           'source':'directory'
 *       ],
 *       'ou=departments,dc=someu,dc=edu':[
 *           'singular':'department',
 *           'plural':'departments',
 *           'rdnAttribute':'ou',
 *           'source':'directory'
 *       ],
 *       'ou=accounts,dc=someu,dc=edu':[
 *           'singular':'account',
 *           'plural':'accounts',
 *           'rdnAttribute':'cn',
 *           'source':'ad'
 *       ]
 *  ]
 * </pre>
 *
 * You can search the branches of the directories as follows:
 *
 * <pre>
 *   // People
 *   // Find 1 person matching params
 *   def person = directoryService.findPersonWhere('uid': '1234')
 *   // Find all people matching params
 *   def people = directoryService.findPeopleWhere('sn': 'nguyen')
 *
 *   // Departments
 *   // Find 1 department matching params
 *   def department = directoryService.findDepartmentWhere('departmentNumber': '54321')
 *   // Find all departments matching params
 *   def departments = directoryService.findDepartmentsWhere('someuEduDepartmentParent': '54321')
 *
 *   // AD Accounts (which are in a different directory server)
 *   // Find 1 account matching params
 *   def account = directoryService.findAccountWhere('sAMAccountName': 'lrockwell')
 *   // Find all accounts matching the provided filter
 *   def filter = directoryService.createFilter('(&(sAMAccountName=wa*))')
 *   def accounts = directoryService.findAccountsWhere(filter)
 * </pre>
 *
 * Find more examples in the official documentation.
 *
 * @author Lucas Rockwell
 */
class DirectoryService {

    static transactional = false

    /* Logger for this class. */
    private Logger log = Logger.getLogger(getClass().name)

    /* ConfigSlurper. */
    def config

    /* Holds the current LDAPConnection. */
    private conn = [:]

    /* Map that holds any ServerSet objects that are created during the duration
       of this object's life. */
    private Map serverSets = [:]

    /**
     * Creates a new DirectoryService object which parses the passed in
     * {@code config} as the overall config for the object. The optional
     * {@code preventConfigMods} boolean can be passed to lock down the config
     * so that it cannot be modified at runtime. It defaults to {@code true}.
     *
     * @param configFile            Path to the config file
     * @param preventConfigMods     {@code true} if the config should not be
     * modifiable, and {@code false} otherwise. Defaults to {@code true}.
     */
    public DirectoryService(configFile, preventConfigMods=true) {
        config = new ConfigSlurper().parse(new File(configFile).toURL())
        if (preventConfigMods) {
            this.preventConfigMods()
        }
    }

    /**
     * Sets the {@code sources} and {@code dit} parts of the config as
     * unmodifiable Maps using the Collections.unmodifiableMap() method. Use
     * this to prevent the modification of the config map during runtime.
     */
    def preventConfigMods() {
        config.directoryservice.sources.each {key, value ->
            println key
            println value
            config.directoryservice.sources[key] =
                Collections.unmodifiableMap(value)
        }
        config.directoryservice.dit.each {key, value ->
            config.directoryservice.dit[key] =
                Collections.unmodifiableMap(value)
        }
    }

    /**
     * Catches the following methods:
     *
     * <ul>
     *   <li>find&lt;plural&gt;Where(Map of attribute/value pairs)</li>
     *   <li>find&lt;singular&gt;Where(Map of attribute/value pairs)</li>
     *   <li>find&lt;plural&gt;UsingFilter(Filter)</li>
     *   <li>get&lt;singular&gt;(RDN attribute value)</li>
     * </ul>
     */
    def methodMissing(String name, args) {
        if (args) {
            def method

            if (name.matches(/^get(\w+)$/) && args[0] instanceof String) {
                method = directoryServiceConfig.dit.find {
                    name.matches(/^get${it.value.singular?.capitalize()}$/)
                }
                if (method) {
                    def dit = directoryServiceConfig.dit[method.key]
                    return args.size() > 1 ?
                        findEntry(method.key, [(dit.rdnAttribute):args[0]], args[1]) : 
                            findEntry(method.key, [(dit.rdnAttribute):args[0]])
                }
            }
            else if (name == 'findSubentriesWhere' && args.size() > 1 ) {
                // args[0] must have the DN, args[1] is the map, and
                // args[2] would be the ldapControls.
                if (args[1] instanceof LDAPFilter) {
                    return args.size() > 2 ?
                        findEntriesUsingFilter(args[0], args[1], args[2]) :
                            findEntriesUsingFilter(args[0], args[1])
                }
                else {
                    return args.size() > 2 ?
                        findEntries(args[0], args[1], args[2]) :
                            findEntries(args[0], args[1])
                }
            }

            if (args[0] instanceof Map) {
                // If it is a find*, we want to inspect it...
                if (name.matches(/^find(\w+)*$/)) {
                    // Check for find*Where (plural)
                    method = directoryServiceConfig.dit.find {
                        name.matches(/^find${it.value.plural?.capitalize()}Where*$/)
                    }
                    if (method) {
                        return args.size() > 1 ?
                            findEntries(method.key, args[0], args[1]) :
                                findEntries(method.key, args[0])
                    }
                    else {
                        // Didn't find plural, so check for singular
                        method = directoryServiceConfig.dit.find {
                            name.matches(/^find${it.value.singular?.capitalize()}Where*$/)
                        }
                        if (method) {
                            // No need to worry about sort because we
                            // only return one max, anyway.
                            return args.size() > 1 ?
                                findEntry(method.key, args[0], args[1]) :
                                    findEntry(method.key, args[0])
                        }
                    }
                }
            }
            else if (args[0] instanceof LDAPFilter || args[0] instanceof String) {
                def ldapFilter = args[0]
                if (args[0] instanceof String) {
                    ldapFilter = LDAPFilter.create(args[0])
                }
                method = directoryServiceConfig.dit.find {
                    name.matches(/^find${it.value.plural?.capitalize()}Where*$/)
                }
                if (method) {
                    //return findAllUsingFilter(method.key, args[0])
                    return args.size() > 1 ?
                        findEntriesUsingFilter(method.key, ldapFilter, args[1]) :
                            findEntriesUsingFilter(method.key, ldapFilter)
                }
                else {
                    // Didn't find plural, so check for singular
                    method = directoryServiceConfig.dit.find {
                        name.matches(/^find${it.value.singular?.capitalize()}Where*$/)
                    }
                    if (method) {
                        // No need to worry about sort because we
                        // only return one max, anyway.
                        def foundEntries = args.size() > 1 ? findEntriesUsingFilter(method.key, ldapFilter, args[1]) :
                            findEntriesUsingFilter(method.key, ldapFilter)
                        if (foundEntries?.size() >= 1) {
                            return foundEntries[0]
                        }
                        else {
                            return null
                        }
                    }
                }
            }
        }
        throw new MissingMethodException(name, this.getClass(), args)
    }


    /**
     * Performs a find based on the passed in baseDN and args and returns
     * the first entry found. If no entry is found, it returns null.
     *
     * @param baseDN        The base DN to use in the search.
     * @param args          The map of key:value pairs which will be turned
     * into an AND filter.
     * @return A DirectoryServiceEntry, which is the first result of the resulting
     * search.
     */
    def findEntry(String baseDN, Map args, ldapControls=[:]) {
        def entries = findEntries(baseDN, args, ldapControls)
        if (entries) {
            return entries[0]
        }
        return null

    }

    /**
     * Performs a find based on the passed in {@code baseDN} and {@code args}
     * and returns a List of all of the results.
     *
     * @param baseDN        The base DN to use in the search.
     * @param args          The map of key:value pairs which will be turned
     *                      into an AND filter.
     * @param ldapControls  Any additional LDAP Controls to pass in, like sorting
     *                      attributes to return, etc.
     * @return List of LdapServiceEntry objects.
     */
    def findEntries(String baseDN, Map args, ldapControls=[:]) {
        return findEntriesUsingFilter(baseDN, andFilterFromArgs(args), ldapControls)
    }

    /**
     * Performs a find based on the passed in {@code baseDN} and {@code filter}
     * and returns a List of all of the results.
     *
     * @param baseDN        The base DN to use in the search.
     * @param filter        The com.unboundid.ldap.sdk.Filter to use in the
     *                      search request
     * @param ldapControls  Any additional LDAP Controls to pass in, like sorting
     *                      attributes to return, etc.
     * @return List of LdapServiceEntry objects.
     */
    def findEntriesUsingFilter(String baseDN, LDAPFilter filter, ldapControls=[:]) {
        def dit = directoryServiceConfig.dit[baseDN]
        def attrsToReturn = (String[])['*']
        if (ldapControls?.attrs) {
            attrsToReturn = (String[])ldapControls?.attrs
        }
        else if (dit?.attributes) {
            attrsToReturn = (String[])dit?.attributes
        }
        List<SearchResultEntry> entries = searchUsingFilter(baseDN, filter.toString(),
            ldapControls, attrsToReturn)
        def list = []
        entries.each {
            list.add(new DirectoryServiceEntry(it, baseDN))
        }
        return list
    }

    /**
     * Creates an AND filter from the passed in map of args.
     *
     * @param args          The arguments to use for creating the filter.
     * @return An AND LDAPFilter that was created from the passed in map of args.
     */
    def andFilterFromArgs(Map args) {
        LDAPFilter filter

        if (args.length == 1) {
            filter = LDAPFilter.createEqualityFilter(args.key, args.value)
            return filter
        }
        else {
            def filters = []
            args.each { arg ->
                filters.push(
                    LDAPFilter.createEqualityFilter(arg.key, arg.value))
            }
            filter = LDAPFilter.createANDFilter(filters)
            return filter
        }

    }

    /**
     * Tries to create an LDAPFilter from the passed in string.
     *
     * @param filterString   String to be converted into an LDAPFilter.
     * @return An LDAPFilter that was created from the filterString.
     */
    def createFilter(String filterString) {
        try {
            def filter = LDAPFilter.create(filterString)
            return filter
        }
        catch (LDAPException e) {
            log.error "Error creating filter from string: $e.message", e
        }
        return filterString
    }

    /**
     * Saves the passed in DirectoryServiceEntry to the directory that
     * is associated with the given DN. This could get tricky if you have
     * objects from competing directories that might have the same parent DN
     * of the passed in object. But for now, this is the only way I know how to
     * do this.
     *
     * Note: A future version of DirectoryServiceEntry will have an errors
     * object that implements the Spring Errors interface, so the error handling
     * will change.
     *
     * @param entry         The DirectoryServiceEntry to save.
     * @return {@code true} on success, {@code false} otherwise.
     * @throws
     */
    def save(DirectoryServiceEntry entry) {
        def conn = connection(entry?.baseDN)
        if (conn) {
            if (entry.modifications) {
                try {
                    conn.modify(entry?.entry?.getDN(), entry.modifications)
                    entry.cleanupAfterSave()
                    return true
                }
                catch (LDAPException e) {
                    log.error "Could not save/modify ${entry?.entry?.getDN()}: $e.message", e
                    entry.errors['save'] = e.getMessage()
                }
                finally {
                    if (conn?.metaClass.respondsTo(conn, "getHealthCheck")) {
                        conn?.releaseConnection()
                    }
                    else {
                        conn?.close()
                    }
                }
            }
            else {
                entry.cleanupAfterSave()
                log.info "There were no modifications in ${entry.dn}, so no reason to save."
            }
        }
        else {
            def reason =
                "Could not save/modify because a connection to the directory server could not be established. "
            entry.errors['save'] = reason
            log.info reason
        }
        return false
    }

    /**
     * Returns the ServerSet associated with the provided sourceName.
     * DirectoryService uses a FailoverServerSet.
     *
     * Since this there may be numerous directory servers specified as part
     * of the configuration for the DirectoryService, the method stashes them
     * away in an internal map that is consulted each time a request is made.
     * If there is already a ServerSet associated with the provided source,
     * it returns that. If not, it creates a new one, stores it for later,
     * and then returns it.
     *
     * @param sourceName        The name of the source to use for creating
     *                          the ServerSet.
     * @return A ServerSet which can be used for getting a connection.
     * @see #serverSetForSource(String addresses, String ports, boolean useSSL, boolean trustSSLCert, boolean followReferrals=true)
     */
    def serverSetForSourceName(sourceName) {
        if (serverSets[sourceName]) {
            return serverSets[sourceName]
        }
        else {
            def source = directoryServiceConfig.sources[sourceName]
            def serverSet = serverSetForSource(
                source.address,
                source.port,
                source.useSSL,
                source.trustSSLCert,
                source.followReferrals
            )
            serverSets[sourceName] = serverSet
            return serverSet
        }
    }

    /**
     * Creates a new FailoverServerSet from the passed in args.
     *
     * @param addresses         One or more addresses, separated by a "," if
     *                          more than one.
     * @param ports             One or more ports, separated by a "," if more
     *                          than one. The number of ports and addresses must match.
     * @param useSSL            Whether or not to use SSL for the connection.
     * @param trustSSLCert      Whether or not to implicitly trust the SSL
     *                          certificate. If this is {@code false}, then your
     *                          JVM must trust the SSL certificate.
     * @param followReferrals   Whether or not to follow referrals. This defaults
     *                          to true.
     * @return A FailoverServerSet which is created based on the passed in args.
     */
    def serverSetForSource(String addresses, String ports,
        boolean useSSL, boolean trustSSLCert, boolean followReferrals=true) {

        final String[] addressesArray =
            addresses?.replaceAll(' ', '')?.split(',')

        final String[] portsStringArray =
            ports?.replaceAll(' ', '')?.split(',')

        int[] portsIntArray = new int[portsStringArray.length]

        if (portsStringArray.length == 1) {
            portsIntArray[0] = Integer.parseInt(portsStringArray[0])
        }
        else {
            portsStringArray.eachWithIndex() { obj, i ->
                portsIntArray[i] = Integer.parseInt(obj)
            }
        }

        LDAPConnectionOptions options = new LDAPConnectionOptions()
        options.setFollowReferrals(followReferrals)

        try {
            if (useSSL) {
                SSLUtil sslUtil
                if (trustSSLCert) {
                    sslUtil = new SSLUtil(new TrustAllTrustManager())
                }
                else {
                    sslUtil = new SSLUtil()
                }
                return new RoundRobinServerSet(
                    addressesArray, portsIntArray, sslUtil.createSSLSocketFactory(), options)
            }
            else {
                return new RoundRobinServerSet(addressesArray, portsIntArray, options)
            }
        }
        catch (GeneralSecurityException gse) {
            log.error "Error connecting via SSL: $gse", gse
            return null
        }
    }

    /**
     * Get a connection from the ServerSet that is associated with the passed
     * in base.
     *
     * This method also performs the bind to the server using the bindDN and
     * bindPassword associated with the source for provided base. If there
     * is no bindDN in the source, then the connection is returned
     * unauthenticated, i.e., it is an anonymous bind.
     *
     * @param base      The search base that will be used to look
     *                  up the source map, and then the corresponding serverSet
     *                  for that source.
     * @return The LDAPConnection object.
     */
    def connection(String base) {
        def sourceName = sourceNameFromBase(base)
        def serverSet = serverSetForSourceName(sourceName)
        def source = directoryServiceConfig.sources[sourceName]
        if (source.useConnectionPool) {
            if (!conn[sourceName] || conn[sourceName]?.isClosed()) {
                if (source.bindDN) {
                    SimpleBindRequest bindRequest =
                        new SimpleBindRequest(source.bindDN, source.bindPassword)
                    conn[sourceName] = new LDAPConnectionPool(serverSet, bindRequest,
                        source.initialConnections, source.maxConnections)
                }
                else {
                    SimpleBindRequest bindRequest =
                        new SimpleBindRequest()
                    conn[sourceName] = new LDAPConnectionPool(serverSet, bindRequest,
                        source.initialConnections, source.maxConnections)
                }
            }
            return conn[sourceName]
        }
        else {
            def localConn = serverSet?.getConnection()
            // If there is a bindDN, then bind, otherwise, treat as
            // anonymous.
            if (source.bindDN) {
                localConn?.bind(source.bindDN, source.bindPassword)
            }
            return localConn
        }
    }

    /**
     * Returns the {@code ldap.sources} Map based on the passed in
     * base, which should be a key in the {@code ldap.dit} Map.
     *
     * @param base      The search base that will be used to look
     *                  up the source map.
     * @return The map which contains the source for this base.
     */
    def sourceNameFromBase(String base) {
        def dit = directoryServiceConfig.dit[base]
        // Might be looking for subentries, so we need to get the parent
        // and try again
        if (!dit) {
            return sourceNameFromBase(new DN(base)?.getParent().toString())
        }
        return dit.source
    }

    /**
     * Takes in the passed base and returns the corresponding
     * source from grails.plugins.directoryservice.source.
     *
     * @param base      The search base which will be used as the key
     *                  for the lookup in the grails.plugins.directoryservice.source.
     * @return The Map which corresponds to the source for the provided
     * base.
     */
    def sourceFromBase(String base) {
        def dit = directoryServiceConfig.dit[base]
        return directoryServiceConfig.sources[dit.source]
    }

    /**
     * Returns a list of SearchResultEntry objects. Returns an empty
     * list if any exceptions are thrown. This method returns
     * #searchUsingFilter(final String base, final String filter, String... attrs='*')
     *
     * @param base          The search base.
     * @param attribute     The attribute to search for.
     * @param value         The value of attribute.
     * @param ldapControls  Any additional LDAP Controls to pass in, like sorting
     *                      attributes to return, etc.
     * @param attrs         The attributes to return. By default it will return
     *                      everything the bind has access to.
     * @return A List of SearchResultEntry objects.
     * @see #searchUsingFilter(final String base, final String filter, String... attrs='*')
     */
    def search(String base, String attribute, String value, ldapControls=[:],
        String... attrs='*') {
        def filter = createFilter("(${attribute}=${value})")
        return searchUsingFilter(base, filter.toString(), ldapControls, attrs)
    }

    /**
     * Returns a list of SearchResultEntry objects. Returns an empty
     * list if any exceptions are thrown.
     *
     * It performs the search using a scope of SearchScope.SUB unless a scope
     * is provided in the ldapControls. Options are 'base' (for
     * SearchScope.BASE), 'one' (for SearchScope.ONE), 'sub' (for
     * SearchScope.SUB), and 'subtree' or 'subordinate', or 'subordinateSubtree'
     * (for SearchScope.SUBORDINATE_SUBTREE).
     *
     * @param base          The search base.
     * @param attribute     The attribute to search for.
     * @param value         The value of attribute.
     * @param ldapControls  Any additional LDAP Controls to pass in, like sorting
     *                      attributes to return, etc.
     * @param attrs         The attributes to return. By default it will return
     *                      everything the bind has access to.
     * @return A List of SearchResultEntry objects.
     */
    def searchUsingFilter(final String base, final String filter,
        ldapControls=[:], String... attrs='*') {
        def searchScope = SearchScope.SUB
        if (ldapControls?.scope) {
            if (ldapControls.scope == 'base') {
                searchScope = SearchScope.BASE
            }
            else if (ldapControls.scope == 'one') {
                searchScope = SearchScope.ONE
            }
            else if (ldapControls.scope.contains('subtree') ||
                ldapControls.scope.contains('subordinate')) {
                searchScope = SearchScope.SUBORDINATE_SUBTREE
            }
        }

        SearchRequest searchRequest = new SearchRequest(
                base,
                searchScope,
                filter,
                ldapControls?.attrs ? (String[])ldapControls?.attrs : attrs)
        
        if (ldapControls && ldapControls instanceof Map) {
            if (ldapControls.sort) {
                searchRequest.addControl(new ServerSideSortRequestControl(
                    new SortKey(ldapControls.sort)))
            }
            if (ldapControls.sizeLimit && ldapControls.sizeLimit > 0) {
                searchRequest.setSizeLimit(ldapControls.sizeLimit)
            }
            if (ldapControls.timeLimit && ldapControls.timeLimit > 0) {
                searchRequest.setTimeLimitSeconds(ldapControls.timeLimit)
            }
        }
        def conn = connection(base)
        try {
            List<SearchResultEntry> entries = []
            if (ldapControls.pagedSearch) {
                ASN1OctetString resumeCookie = null
                int numSearches = 0
                int numEntries = 0
                while (true) {
                    searchRequest.setControls(
                        new SimplePagedResultsControl(
                            ldapControls?.pageSize ?: 500, resumeCookie))
                    numSearches++
                    SearchResult results = conn.search(searchRequest)
                    entries += results.getSearchEntries()
                    numEntries += results.getEntryCount()
                    SimplePagedResultsControl responseControl =
                            SimplePagedResultsControl.get(results)
                    if (responseControl?.moreResultsToReturn()) {
                        resumeCookie = responseControl.getCookie()
                    }
                    else {
                        break
                    }
                }
                log.info "Paged Search - pages completed: ${numSearches}; entries found: ${numEntries}"
            }
            else {
                SearchResult results = conn.search(searchRequest)
                entries = results.getSearchEntries()
            }
            return entries
        }
        catch (LDAPSearchException lse) {
            log.warning "Exception while searching: ${lse.message} - ${lse}"
            List<SearchResultEntry> entries = lse.getSearchEntries()
            return entries
        }
        finally {
            if (conn?.metaClass.respondsTo(conn, "getHealthCheck")) {
                conn?.releaseConnection()
            }
            else {
                conn?.close()
            }
        }
    }

    /**
     * Accessor for the {@code conn} Map instance variable.
     *
     * @return the {@code conn} Map of any connections that have been set up
     *    by the DirectoryService.
     */
    def connections() {
        return conn
    }

    /**
     * DRY place to get config
     * @return
     */
    private Map getDirectoryServiceConfig() {
        config.directoryservice
    }

}
