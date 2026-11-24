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
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

import com.unboundid.asn1.ASN1OctetString
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.FailoverServerSet
import com.unboundid.ldap.sdk.Filter as LDAPFilter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPSearchException
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldap.sdk.RDN
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
import com.unboundid.ldif.LDIFModifyChangeRecord
import com.unboundid.util.StaticUtils
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

    /*
     * Default change record shape: the Canonical Identity ciChangeRecord
     * schema. A changes dit item can override any of these. See recordChange().
     */
    static final List<String> DEFAULT_CHANGE_OBJECT_CLASS = ['top', 'ciChangeRecord']
    static final Map<String, String> DEFAULT_CHANGE_ATTRIBUTES = [
        target  : 'ciChangeTarget',
        time    : 'ciChangeTime',
        type    : 'ciChangeType',
        changes : 'ciChanges',
        subject : 'ciChangeSubject',
        id      : 'ciID',
        modifier: 'ciModifiersName',
        reason  : 'ciModifyReason'
    ]

    /* Changes recorded since each changelog was last purged, by base DN. */
    private final Map<String, AtomicInteger> changesSincePurge = new ConcurrentHashMap<>()

    /* Configuration problems already warned about, so each is logged once. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet()

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
     *   <li>find&lt;plural&gt;UsingFilter(Filter)</li>>
     *   <li>get&lt;singular&gt;(RDN attribute value)</li>
     *   <li>get&lt;singular&gt;(DN of object)</li>
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
                    def value = args[0]
                    if (value?.contains('=')) {
                        // This can still fail, but we are assuming a DN now.
                        def dn  = new DN(value)
                        def rdn = dn?.getRDN()
                        def values = rdn?.getAttributeValues()
                        if (values?.size() > 0) {
                            value = values[0]
                        }
                    }
                    return args.size() > 1 ?
                        findEntry(method.key, [(dit.rdnAttribute):value], args[1]) :
                            findEntry(method.key, [(dit.rdnAttribute):value])
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
            log.log(Level.SEVERE, "Error creating filter from string: $e.message", e)
        }
        return filterString
    }

    /**
     * Takes the provided {@code attr}, extracts a list of values from the
     * provided {@code entry} object, then finds the actual value of that
     * object from the directory.
     *
     * attribute, and then for each value, it fetches the underlying object
     * and returns a list of {@code DirectoryServiceEntry} objects.
     *
     * It will return an empty list if there was no attr on the entry, or if no
     * corresponding entries where found. It will also return an empty list if
     * the DN object points to an area of the directory that is not specified
     * in the {@code directoryservice.dit} section of the config. For instance,
     * the {@code seeAlso} attribute can point to any DN object in the directory
     * yet those values may point to objects that are not coinfigured.
     *
     * This is being done in DirectoryService because we want to keep connection
     * activities out of the DirectoryServiceEntry object.
     *
     * @param entry         The entry that holds the attr.
     * @param attr          The name of the DN attribute.
     * @param pattern       A pattern to match against the DN string. Defaults
     *                      to none.
     * @param attrs         List of attrs to return for the entry. Defaults to
     *                      all.
     * @return A list of objects that the DN attribute values, point to. 
     */
    def attrAsObjects(DirectoryServiceEntry entry, String attr,
        String pattern=null, List attrs=['*']) {
        def objects = []

        def values = (List)entry?.entry?.getAttributeValues(attr)
        values.each {value ->
            if (!pattern || (pattern && value.contains(pattern))) {
                def filter   = '(objectClass=*)'
                def controls = [scope: 'base']
                def results  = searchUsingFilter(
                    value, filter, controls, attrs?.toArray(new String[0]))

                if (results?.size() == 1) {
                    def dn     = new DN(results[0].getDN())
                    def baseDN = dn?.getParentString()
                    def dse = new DirectoryServiceEntry(results[0], baseDN)
                    objects.push(dse)
                }
                else {
                    log.info "Found more than one entry for ${dn} search."
                }
            }
        }
        return objects
    }

    /**
     * Saves the passed in DirectoryServiceEntry to the directory that is
     * associated with the entry's DN.
     *
     * @see The {@code save(DirectoryServiceEntry entry)} method.
     * 
     * @param entry         The DirectoryServiceEntry to save.
     * @return {@code true} on success, {@code false} otherwise.
     */
    def modify(DirectoryServiceEntry entry) {
        return save(entry)
    }

    /**
     * Saves the passed in DirectoryServiceEntry to the directory that
     * is associated with the entry's DN. This could get tricky if you have
     * objects from competing directories that might have the same parent DN
     * of the passed in object. But for now, this is the only way I know how to
     * do this.
     *
     * If the entry's source has {@code trackChanges: true}, a successful save
     * also writes a change record describing it. See
     * {@link #recordChange(String, String, List, DirectoryServiceEntry, Map)}.
     *
     * @param entry         The DirectoryServiceEntry to save.
     * @param changeInfo    Optional details for the change record:
     *                      {@code reason} (why the change was made) and
     *                      {@code modifier} (who or what made it).
     * @return {@code true} on success, {@code false} otherwise.
     */
    def save(DirectoryServiceEntry entry, Map changeInfo=[:]) {
        String dn = entry?.entry?.getDN()
        // Captured before cleanupAfterSave() replaces it, for the change record.
        List<Modification> applied = entry?.modifications
        boolean saved = withConnection(entry?.baseDN) { conn ->
            if (!conn) {
                def reason =
                    "Could not save/modify because a connection to the directory server could not be established. "
                entry.errors['save'] = reason
                log.info reason
                return false
            }
            if (!applied) {
                entry.cleanupAfterSave()
                log.info "There were no modifications in ${entry.dn}, so no reason to save."
                return false
            }
            try {
                conn.modify(dn, applied)
                entry.cleanupAfterSave()
                return true
            }
            catch (LDAPException e) {
                log.log(Level.SEVERE, "Could not save/modify ${dn}: $e.message", e)
                entry.errors['save'] = e.getMessage()
                return false
            }
        }
        // Recorded after the connection is released.
        if (saved) {
            recordChange('modify', dn, applied, entry, changeInfo)
        }
        return saved
    }

    /**
     * Writes a change record describing a change, if the source the change was
     * made to tracks changes. This gives directories without a changelog
     * (Active Directory) a searchable history for troubleshooting, without a
     * separate process: like the UnboundID in-memory server's changelog, the
     * record is written right after the change itself.
     *
     * Recording is best-effort. Any failure is logged and swallowed, so it
     * never fails the change being recorded.
     *
     * Configuration: a source opts in with {@code trackChanges}, and names the
     * dit item (by its {@code singular} name) where its records go. That dit
     * item says how records are named and shaped, and how long they are kept.
     * Everything after {@code source} is optional; the values shown are the
     * defaults, which match the Canonical Identity ciChangeRecord schema:
     * <pre>
     *   directoryservice.sources = [
     *       'ad':[
     *           ...
     *           trackChanges: true,
     *           changesDITItem: 'changeRecord'
     *       ]
     *   ]
     *   directoryservice.dit = [
     *       'ou=changelog,dc=someu,dc=edu':[
     *           singular: 'changeRecord',
     *           plural: 'changeRecords',
     *           rdnAttribute: 'cn',
     *           source: 'directory',
     *           objectClass: ['top', 'ciChangeRecord'],
     *           changeAttributes: [
     *               target: 'ciChangeTarget',     // required
     *               time: 'ciChangeTime',         // required
     *               type: 'ciChangeType',         // required
     *               changes: 'ciChanges',
     *               subject: 'ciChangeSubject',
     *               id: 'ciID',                   // copied from the changed entry
     *               modifier: 'ciModifiersName',
     *               reason: 'ciModifyReason'
     *           ],
     *           retentionDays: 395,  // records older than this are purged (0 = never)
     *           purgeEvery: 100,     // purge after every N recorded changes
     *           purgeBatch: 50,      // most records deleted per purge
     *           subjectAttributes: ['member', 'uniqueMember'] // values recorded as subjects
     *       ]
     *   ]
     * </pre>
     *
     * {@code changeAttributes} only needs the names that differ from the
     * defaults. Set an optional one to '' if your schema has no such
     * attribute, and that part of the record is left out. {@code id} names the
     * identifier attribute both on the changed entry and on the record; add
     * {@code targetId} if the changed entry calls it something else.
     *
     * Several sources can share one changes dit item, so one search finds
     * changes made to any of them. Changes to entries in a changes branch are
     * never recorded.
     *
     * Each record is named {@code <rdnAttribute>=<time>-<uuid>} under the
     * changes branch and holds: the target DN as it was, the time (whole
     * seconds, in the "yyyyMMddHHmmss.0Z" form AD and OpenDJ both accept),
     * the change type, the change as an LDIF change record (binary), the
     * changed entry's id, the modifier (from {@code changeInfo}, or the bind
     * DN of the changed entry's source), the reason, and the subjects: the
     * target's DN and id plus every value added or removed in a subject
     * attribute, such as the members of a group.
     *
     * @param changeType    add, modify, delete or modrdn.
     * @param targetDN      The DN of the entry that was changed.
     * @param mods          The modifications that were applied.
     * @param target        The changed entry, for its source and id. May be
     *                      null, in which case the source is found from the DN.
     * @param changeInfo    Optional {@code reason} and {@code modifier}.
     * @return {@code true} if a record was written, {@code false} otherwise.
     */
    boolean recordChange(String changeType, String targetDN,
        List<Modification> mods, DirectoryServiceEntry target=null,
        Map changeInfo=[:]) {

        String sourceName = sourceNameOrNull(target?.baseDN ?: targetDN)
        Map changelog = changelogForSource(sourceName)
        if (!changelog || isUnder(targetDN, changelog.base as String)) {
            return false
        }
        Map settings = changelog.settings as Map
        Map names = changelog.names as Map

        try {
            Date now = nextRecordTime()
            // The RDN value starts with the time to the millisecond, so records
            // sort in order, and ends with a UUID, so it is unique.
            String rdnAttribute = changelog.rdnAttribute
            String rdnValue = StaticUtils.encodeGeneralizedTime(now) + '-' +
                UUID.randomUUID().toString()
            Entry record = new Entry(new DN(new RDN(rdnAttribute, rdnValue),
                new DN(changelog.base as String)))
            record.addAttribute('objectClass', changelog.objectClasses as String[])
            record.addAttribute(rdnAttribute, rdnValue)
            record.addAttribute(names.target as String, targetDN)
            record.addAttribute(names.time as String, changeTime(now))
            record.addAttribute(names.type as String, changeType)
            if (names.changes && mods) {
                record.addAttribute(names.changes as String, new LDIFModifyChangeRecord(
                    targetDN, mods).toLDIFString().getBytes('UTF-8'))
            }

            String targetId = (names.targetId || names.id) ?
                target?.entry?.getAttributeValue((names.targetId ?: names.id) as String) : null
            if (names.id && targetId) {
                record.addAttribute(names.id as String, targetId)
            }

            if (names.subject) {
                List<String> subjectAttributes = (setting(settings,
                    'subjectAttributes', ['member', 'uniqueMember']) as List)*.toLowerCase()
                List<String> subjects = [targetDN]
                if (targetId) {
                    subjects << targetId
                }
                mods?.each { Modification mod ->
                    String name = mod.attributeName.toLowerCase().split(';')[0]
                    if (name in subjectAttributes) {
                        subjects.addAll(mod.values as List)
                    }
                }
                record.addAttribute(names.subject as String,
                    subjects.unique { it.toLowerCase() } as String[])
            }

            String modifier = changeInfo?.modifier ?:
                setting(directoryServiceConfig.sources[sourceName] as Map, 'bindDN', null)
            if (names.modifier && modifier) {
                record.addAttribute(names.modifier as String, modifier as String)
            }
            if (names.reason && changeInfo?.reason) {
                record.addAttribute(names.reason as String, changeInfo.reason as String)
            }

            withConnection(changelog.base as String) { it.add(record) }
        }
        catch (Exception e) {
            log.warning "Could not record the ${changeType} of ${targetDN}: ${e.message}"
            return false
        }

        int purgeEvery = setting(settings, 'purgeEvery', 100) as int
        AtomicInteger count = changesSincePurge.computeIfAbsent(
            (changelog.base as String).toLowerCase()) { new AtomicInteger(0) }
        if (count.incrementAndGet() >= purgeEvery) {
            count.set(0)
            purgeChangelog(changelog)
        }
        return true
    }

    /**
     * Deletes expired change records from every changes branch that a source
     * tracking changes uses: records older than the branch's
     * {@code retentionDays} (default 395, about 13 months), at most
     * {@code purgeBatch} (default 50) per branch per call. Called
     * automatically every {@code purgeEvery} recorded changes, so no separate
     * purge process is needed; it can also be called directly. Best-effort:
     * failures are logged.
     *
     * @return The number of change records deleted.
     */
    int purgeChangeRecords() {
        return changelogs().sum(0) { purgeChangelog(it) } as int
    }

    /** Purges one changes branch. See {@link #purgeChangeRecords()}. */
    private int purgeChangelog(Map changelog) {
        Map settings = changelog.settings as Map
        int retentionDays = setting(settings, 'retentionDays', 395) as int
        if (retentionDays <= 0) {
            return 0
        }

        int deleted = 0
        try {
            Date cutoff = new Date(System.currentTimeMillis() -
                retentionDays * 24L * 60L * 60L * 1000L)
            String filter = LDAPFilter.createANDFilter(recordFilters(changelog) +
                LDAPFilter.createLessOrEqualFilter(changelog.names.time as String,
                    changeTime(cutoff))).toString()
            def expired = searchUsingFilter(changelog.base as String, filter,
                [scope: 'one', sizeLimit: setting(settings, 'purgeBatch', 50) as int],
                changelog.rdnAttribute as String)
            withConnection(changelog.base as String) { conn ->
                expired.each {
                    conn.delete(it.getDN())
                    deleted++
                }
            }
        }
        catch (Exception e) {
            log.warning "Could not purge change records in ${changelog.base}: ${e.message}"
        }
        return deleted
    }

    /**
     * Finds the change records for a subject: a DN or id that was the target
     * of a change, or was added to or removed from a subject attribute such
     * as a group's members. Answers questions like "why did this person lose
     * access to these groups on 12/15?". Searches every changes branch that a
     * source tracking changes uses. In a branch whose records have no subject
     * attribute, it matches on the target DN instead.
     *
     * @param subject   The DN or id to look for.
     * @param since     Optional earliest change time.
     * @param until     Optional latest change time.
     * @return The matching change records, oldest first, as
     * DirectoryServiceEntry objects. The LDIF of each change is in the binary
     * changes attribute, e.g. {@code record.entry.getAttributeValueBytes('ciChanges')}.
     */
    List<DirectoryServiceEntry> changesFor(String subject, Date since=null,
        Date until=null) {

        List<List> found = []
        changelogs().each { Map changelog ->
            Map names = changelog.names as Map
            List<LDAPFilter> filters = recordFilters(changelog)
            filters << LDAPFilter.createEqualityFilter(
                (names.subject ?: names.target) as String, subject)
            if (since) {
                filters << LDAPFilter.createGreaterOrEqualFilter(names.time as String, changeTime(since))
            }
            if (until) {
                filters << LDAPFilter.createLessOrEqualFilter(names.time as String, changeTime(until))
            }
            searchUsingFilter(changelog.base as String,
                LDAPFilter.createANDFilter(filters).toString(), [scope: 'one']).each {
                found << [it.getAttributeValue(changelog.rdnAttribute as String),
                          new DirectoryServiceEntry(it, changelog.base as String)]
            }
        }
        // RDN values start with the time to the millisecond.
        return found.sort { it[0] }.collect { it[1] as DirectoryServiceEntry }
    }

    /*
     * Last change record time handed out, in milliseconds. See nextRecordTime().
     */
    private static final AtomicLong lastRecordMillis = new AtomicLong(0L)

    /**
     * The time for a new change record: now, but always at least a millisecond
     * after the previous record from this JVM, so records written in the same
     * millisecond still sort in the order they were written.
     */
    private static Date nextRecordTime() {
        long now = System.currentTimeMillis()
        return new Date(lastRecordMillis.updateAndGet { long last ->
            Math.max(now, last + 1)
        })
    }

    /** Filters matching a changes branch's records by their object classes. */
    private static List<LDAPFilter> recordFilters(Map changelog) {
        return (changelog.objectClasses as List<String>)
            .findAll { !it.equalsIgnoreCase('top') }
            .collect { LDAPFilter.createEqualityFilter('objectClass', it) }
    }

    /**
     * The changes branch for a source, as {@code [base:, settings:,
     * objectClasses:, rdnAttribute:, names:]}, or null when the source does
     * not track changes. A source with {@code trackChanges} but a missing or
     * unknown {@code changesDITItem}, or a dit item without the required
     * target, time and type attribute names, is warned about once, and its
     * changes are not recorded.
     */
    private Map changelogForSource(String sourceName) {
        Map source = sourceName ? directoryServiceConfig.sources[sourceName] as Map : null
        if (!setting(source, 'trackChanges', false)) {
            return null
        }
        String item = setting(source, 'changesDITItem', null)
        def dit = item ? directoryServiceConfig.dit.find { it.value.singular == item } : null
        String problem = null
        Map names = [:]
        if (!dit) {
            problem = item ?
                "its changesDITItem '${item}' is not the singular name of a dit item" :
                "it has no changesDITItem"
        }
        else {
            names = DEFAULT_CHANGE_ATTRIBUTES +
                (setting(dit.value as Map, 'changeAttributes', [:]) as Map)
            List<String> missing = ['target', 'time', 'type'].findAll { !names[it] }
            if (missing) {
                problem = "its changes dit item '${item}' has no attribute names for " +
                    missing.join(', ')
            }
        }
        if (problem) {
            if (warned.add("changes:${sourceName}:${item}:${problem}".toString())) {
                log.warning "Source '${sourceName}' has trackChanges, but ${problem}, " +
                    "so changes to it are not recorded."
            }
            return null
        }
        Map settings = dit.value as Map
        return [
            base         : dit.key as String,
            settings     : settings,
            objectClasses: setting(settings, 'objectClass', DEFAULT_CHANGE_OBJECT_CLASS) as List,
            rdnAttribute : setting(settings, 'rdnAttribute', 'cn') as String,
            names        : names
        ]
    }

    /** The distinct changes branches used by sources that track changes. */
    private List<Map> changelogs() {
        return directoryServiceConfig.sources.keySet()
            .collect { changelogForSource(it as String) }
            .findAll()
            .unique { (it.base as String).toLowerCase() }
    }

    /** The source name for a base or DN, or null if it maps to no dit item. */
    private String sourceNameOrNull(String base) {
        try {
            return sourceNameFromBase(base)
        }
        catch (Exception e) {
            return null
        }
    }

    /** Whether {@code dn} is {@code base} or below it. */
    private static boolean isUnder(String dn, String base) {
        try {
            return new DN(dn).isDescendantOf(new DN(base), true)
        }
        catch (Exception e) {
            return false
        }
    }

    /**
     * A config value, or {@code defaultValue} when it is not set. ConfigSlurper
     * returns an empty ConfigObject rather than null for unset keys, so both
     * count as not set.
     */
    private static def setting(Map map, String key, defaultValue) {
        def value = map?.get(key)
        return (value == null || (value instanceof Map && value.isEmpty())) ?
            defaultValue : value
    }

    /** A change time in the "yyyyMMddHHmmss.0Z" generalized time form. */
    private static String changeTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'.0Z'")
        format.setTimeZone(TimeZone.getTimeZone('UTC'))
        return format.format(date)
    }

    /**
     * Runs the closure with a connection for the provided base, then releases
     * (pooled) or closes (unpooled) the connection.
     */
    private def withConnection(String base, Closure work) {
        def conn = connection(base)
        try {
            return work(conn)
        }
        finally {
            if (conn != null) {
                if (conn.metaClass.respondsTo(conn, "getHealthCheck")) {
                    conn.releaseConnection()
                }
                else {
                    conn.close()
                }
            }
        }
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
            log.log(Level.SEVERE, "Error connecting via SSL: $gse", gse)
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
        return withConnection(base) { conn ->
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
