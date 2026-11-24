# DirectoryService

DirectoryService is a Groovy-based library that allows you to interact with a v3-compliant LDAP server with minimal effort. It is built on top of the [Ping/UnboundID](http://www.pingidentity.com/) [LDAP SDK](https://github.com/pingidentity/ldapsdk) ([UnboundID LDAP SDK for Java](https://docs.ldap.com/ldap-sdk/docs/index.html)  [docs](https://docs.ldap.com/ldap-sdk/docs/javadoc/index.html)).

`DirectoryService` is made up of two key classes, the `DirectoryService` itself, and the `DirectoryServiceEntry` class, which is the class to which results are mapped.

## Install

As for version `0.11.0`, you need to download and build the jar yourself. After cloning this repo, run the following:

    gradle jar

The resulting jar file will be locaed in the `bin/libs` directory.

In the `dependencies` section of a Gradle project's build.gradle, as the following:

```groovy
implementation 'com.unboundid:unboundid-ldapsdk:7.0.4'
implementation files('<path to>/directory-service-jar-0.11.0.jar')
```

## Configure

DirectoryService requires two Maps to be configured:

* `directoryservice.sources`
* `directoryservice.dit`

Since both of these are Groovy Map objects, they must be put in a `.groovy` file (like `grails-app/conf/DSConfig.groovy` or some other ConfigSlurper file), and can not go in a `.properties` file. See [conf/Config.groovy](conf/Config.groovy) as an example.

### Directory Sources Map

The `directoryservice.sources` Map is called sources for a reason: You can have more than one directory source. I once worked where there was a main LDAP directory service, 5 AD domains, and an AD Global Catalog; that's 7 sources! So, this plugin is designed to work with all of your directory sources at once. This is very much modeled after that multiple `dataSource` feature of GORM.

The `directoryservice.sources` uses the following syntax:

```groovy
directoryservice.sources = [
    'source name':[
        address: String // Single address, multiple separated by a ",".
        port: String, // Single port, or multiple separated by a ",". Must match 'address' number.
        useSSL: boolean,
        trustSSLCert: boolean,
        followReferrals: boolean,
        bindDN: String,
        bindPassword: String,
        useConnectionPool: boolean, // Optional
        initialConnections: integer, // Optional
        maxConnections: integer // Optional
    ],
]
```

So, if you have two sources, like an "enterprise directory", and "AD Global Catalog", your `directoryservice.sources` might look like this:

```groovy
directoryservice.sources = [
    'directory':[
        address: 'ldap.someu.edu',
        port: '636',
        useSSL: true,
        trustSSLCert: true
        bindDN: 'cn=some bind DN',
        bindPassword: 'MyPassw0rd!',
        useConnectionPool: true,
        initialConnections: 2,
        maxConnections: 5
    ],
    'adGC':[
        address: 'adgc.someu.edu',
        port: '3269',
        useSSL: true,
        trustSSLCert: true
        bindDN: 'cn=another bind DN',
        bindPassword: 'An0therPassw0rd!'
    ],
]
```

Since this is a `.groovy` file, you can replace any of the above values with references to your config system, or even `System.getenv(...)`

You will see how these sources are referenced in the DIT Map, below.

### Directory DIT Map

The `directoryservice.dit` is another Map that is literally a map of your directory. Of course, you only need to map out the areas of your directory that you want to use. For instance, for the above two sources, we might have a few sections that we want to define: `people`, `departments`, `accounts`, and `groups` (at least one type of group).

The `directoryservice.dit` uses the following syntax:

```groovy
directoryservice.dit = [
    'base DN of the branch':[
        singular: String,
        plural: String,
        rdnAttribute: String,
        source: String,
        attributes: ['*'], // Optional, and default is *. See examples below.
        objectClass: ['top', 'person', 'inetOrgPerson', 'organizationalPerson'] // Optional
    ]
]
```

So, let's define our four branches and relate them to the two sources we defined in `directoryservice.sources`:

```groovy
directoryservice.dit = [
    'ou=people,dc=someu,dc=edu':[
        singular: 'person',
        plural: 'people',
        rdnAttribute: 'uid',
        source:'directory',
        attributes: ['*', 'isMemberOf'],
        objectClass: ['top', 'person', 'inetOrgPerson', 'organizationalPerson']
    ],
    'ou=departments,dc=someu,dc=edu':[
        singular: 'department',
        plural: 'departments',
        rdnAttribute: 'ou',
        source: 'directory',
        attributes: ['*', '+']
    ],
    'ou=groups,dc=someu,dc=edu':[
        singular: 'directoryGroup',
        plural: 'directoryGroups',
        rdnAttribute: 'cn',
        source: 'directory'
    ],
    'ou=accounts,dc=someu,dc=edu':[
        singular: 'account',
        plural: 'accounts',
        rdnAttribute: 'cn',
        source: 'adGC',
        attributes: ['sAMAccountName', 'employeeID', 'givenName', 'sn', 'memberOf']
    ],
    'ou=Groups,dc=someu,dc=edu':[
        singular: 'adGroup',
        plural: 'adGroups',
        rdnAttribute: 'cn',
        source: 'adGC'
    ]
]
```

As you can see above, we have two `ou=groups,dc=someu,dc=edu` branches, one in our enterprise directory, and one in AD. So, we get around this by making one of them -- in this case the AD groups OU -- have an uppercase "G". This works because Map keys are case-sensitive, but thankfully, directories usually do not care about case when specifying a base!

See the Usage section for details on how to perform searches using the information you configured above.

## Usage

### Add DirectoryService


```groovy
def directoryService
```

or

```groovy
DirectoryService directoryService
```

### Search (Find)

As of this version, DirectoryService only supports searching LDAP. Add, update, and delete will be added in forthcoming releases.

Also, unlike GORM, DirectoryService currently only supports `find<singular|plural>Where` methods, not the more sophisticated `<Object>.findBySomeattributeAndAnotherattribute` methods.

At this point you will see why we use a singular and plural name for each branch defined in `directoryService.dit`.

#### Find a Person

Using the example `dit` above, to find a person, you would do the following:

```groovy
def person = directoryService.findPersonWhere(uid:'12345')
def person = directoryService.findPersonWhere(sn:'rockwell', givenName:'lucas')
```

Since `uid` is the RDN attribute for the `ou=people` branch, the first search above is pretty much guaranteed to find just one person. Whereas the second could potentially fine more then one person. However, in both cases, DirectoryService will only return at most <strong>one</strong> record (which will be a DirectoryServiceEntry object). It only returns one because you are using the singular `findPersonWhere`, and semantically that means you only want one person.

This is similar to the GORM `get()` method.

#### Find People

To find more than one person, you would use the plural form that you defined in `dit`. For instance, for your people branch, you would use `findPeopleWhere`:

```groovy
def people = directoryService.findPeopleWhere(sn:'rockwell')
def people = directoryService.findPeopleWhere(departmentNumber:'12345')
```

Or multiple key/value pairs:

```groovy
def people = directoryService.findPeopleWhere(sn:'rockwell', st:'ca')
```

If you are searching for more than one of something, you can sort the result by providing an optional Map with a single key `sort` and the attribute by which you wish to sort:

```groovy
def people = directoryService.findPeopleWhere('sn':'rockwell', [sort:'cn'])
```

Or:

```groovy
def people = directoryService.findPeopleWhere(sn:'rockwell', st:'ca', [sort:'cn'])
```

Of course, the attribute you specify in the sort must be included in the return params in the dit entry (and the bind you are using must also have access to the attribute). Also, at this time, you can only specify one attribute on which to sort. If you want to be able to sort on more than one attribute, please let me know.

### Update (Save)

To be GORM-like, update is implemented as `save`, but unlike GORM, it is implemented on the DirectoryService object, and not the entry object itself. Below is an example of finding, updating, and then saving an entry:

```groovy
def person = directoryService.findPersonWhere('employeeNumber':'23576')
person.sn = 'Franklin-Jackson'
person.cn = ['Sally Franklin-Jackson', 'Franklin-Jackson, Sally']
person.displayName = 'Sally Franklin-Jackson'
person.mail = 'sally.franklin-jackson@somu.edu'
directoryService.save(person)
```

See the official docs and reference guide for more details about saving objects back to the directory.

`save()` returns `true` on success. If the server rejects the change, it returns `false` and puts the reason in `entry.errors['save']`. An object can be edited and saved as many times as needed.

### Update to Match Another Entry

When syncing, you usually have the entry as it should be (built from a source system) and the entry as it is in the directory. `modifyToMatch()` updates the directory entry so that the attributes you list match, exactly as if you had set each differing attribute by hand:

```groovy
def desired = new DirectoryServiceEntry([
    uid: '12345', sn: 'Franklin-Jackson', displayName: 'Sally Franklin-Jackson'
], 'person', directoryService)

def person = directoryService.findPersonWhere(uid: '12345')
person.modifyToMatch(desired, ['sn', 'displayName', 'initials'])
directoryService.save(person)
```

Attributes in the list that the desired entry lacks (`initials` above) are removed. Only listed attributes are compared, so operational attributes such as `entryUUID` are never touched. Any other unsaved changes on the entry are kept and saved with it.

### Change Records

Active Directory has no changelog. A source with `trackChanges: true` gets one: every successful `save()` to it also writes a change record entry describing the change. This gives you a searchable history for troubleshooting, such as "why did this person lose access to these groups on 12/15?", without running a separate changelog process.

The source names the `dit` item where its records go (by its `singular` name), so the changelog is just another branch of your DIT. That `dit` item also says how records are named (`rdnAttribute`) and shaped (`objectClass`, `changeAttributes`), and how long they are kept:

```groovy
directoryservice.sources = [
    'ad':[
        // ...connection settings...
        trackChanges: true,
        changesDITItem: 'changeRecord'
    ]
]

directoryservice.dit = [
    'ou=changelog,dc=someu,dc=edu':[
        singular: 'changeRecord',
        plural: 'changeRecords',
        rdnAttribute: 'cn',
        source: 'directory',
        objectClass: ['top', 'ciChangeRecord'], // optional
        changeAttributes: [  // optional: the record's attribute names
            target: 'ciChangeTarget',     // required
            time: 'ciChangeTime',         // required
            type: 'ciChangeType',         // required
            changes: 'ciChanges',
            subject: 'ciChangeSubject',
            id: 'ciID',                   // copied from the changed entry
            modifier: 'ciModifiersName',
            reason: 'ciModifyReason'
        ],
        retentionDays: 395,  // optional: records older than this are purged (0 = never)
        purgeEvery: 100,     // optional: purge after every N recorded changes
        purgeBatch: 50,      // optional: most records deleted per purge
        subjectAttributes: ['member', 'uniqueMember'] // optional: values recorded as subjects
    ]
]
```

The values shown are the defaults, which match the `ciChangeRecord` class in the Canonical Identity schema (directory-service-tf). To use your own schema, set `objectClass` and only the `changeAttributes` names that differ. Set an optional name to `''` if your schema has no such attribute, and that part of the record is left out; without a `subject` attribute, `changesFor()` matches on the target DN instead. `id` names the identifier attribute on both the changed entry and the record; add `targetId` if the changed entry calls it something else.

Several sources can share one changes `dit` item, so one search finds changes made to any of them, for example both your directory and a corporate forest your sync writes to. Sources without `trackChanges` are not recorded. If `trackChanges` is set but `changesDITItem` is missing or unknown, a warning is logged and nothing is recorded. Changes to the change records themselves are never recorded.

Pass the reason for a change, and who or what made it, when saving:

```groovy
directoryService.save(group, [reason: 'No longer matches the dynamic rule for some group',
                              modifier: 'group-sync'])
```

Each record is named `<rdnAttribute>=<time>-<uuid>` and holds the target DN, the time and type of change, the change itself as LDIF (binary), the target's id, the modifier (by default, the bind DN of the changed source) and reason, and every subject: the target's DN and `ciID`, plus each value added to or removed from a subject attribute such as a group's members. So one search finds every change that affected a person, including changes to the groups they were in:

```groovy
def since   = Date.parse('yyyy-MM-dd', '2026-12-15')
def records = directoryService.changesFor('uid=12345,ou=people,dc=someu,dc=edu', since)
records.each { record ->
    println "${record.ciChangeTime} ${record.ciChangeType} ${record.ciChangeTarget}: ${record.ciModifyReason}"
    println new String(record.entry.getAttributeValueBytes('ciChanges'), 'UTF-8')
}
```

`changesFor()` searches every changes branch in use. Since the changes branch is a `dit` item, its finders work too, e.g. `directoryService.findChangeRecordsWhere(ciChangeTarget: dn)`.

Recording is best-effort: if a record cannot be written, the failure is logged and the save still succeeds. Expired records are purged a few at a time during saves (every `purgeEvery` recorded changes), or you can call `directoryService.purgeChangeRecords()` yourself.

### Add (Create)

Not implemented yet.

### Delete

Although not officially implemented, you can do the following to delete an object:

* Call `directoryService.connection(baseDN)?.delete(DN of object to delete)` and pass it the value of one of the base DNs for the `grails.plugins.directoryservice.dit` map.

So, using the `grails.plugins.directoryservice.dit` map above, to delete a person, you would do the following:

`directoryService.connection('ou=people,dc=someu,dc=edu')?.delete('uid=12345,ou=people,dc=someu,dc=edu')`

Or, if you have the object that you want to delete:

```
def person = directoryService.findPersonWhere(uid:'12345')
directoryService.connection('ou=people,dc=someu,dc=edu')?.delete(person.dn)
```

See the [UnboundID LDAP SDK Java docs](https://www.unboundid.com/products/ldap-sdk/docs/javadoc/index.html) for more info about the capabilities of the SDK.

## License

DirectoryService is licensed under the terms of the [BSD 3-Clause License](http://www.apache.org/licenses/LICENSE-2.0). The UnboundID LDAP SDK is licensed under the terms of three different licenses. Please see the [UnboundID LDAP SDK for Java docs page](https://www.unboundid.com/products/ldap-sdk/docs/javadoc/index.html) for more information.