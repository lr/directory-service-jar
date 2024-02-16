// There is nothing running on my dev machine on
// port 11389, so by doing this we are always testing
// failover! Other, over-the-wire servers have been tested
// as well.
directoryservice.sources = [
    directory:[
        address: 'localhost , localhost',
        port: ' 11389 ,33389',
        useSSL: false,
        trustSSLCert: true,
        followReferrals: true,
        useConnectionPool: true,
        initialConnections: 5,
        maxConnections: 50,
        bindDN: 'cn=Directory Manager',
        bindPassword: 'password'
    ],
    ad:[
        address: 'localhost',
        port: '33268',
        useSSL: false,
        trustSSLCert: true,
        followReferrals: false,
        bindDN: 'cn=AD Manager',
        bindPassword: 'password'
    ],
    adWithPool:[
        address: 'localhost',
        port: '33269',
        useSSL: false,
        trustSSLCert: true,
        followReferrals: false,
        useConnectionPool: true,
        initialConnections: 5,
        maxConnections: 50,
        bindDN: 'cn=AD Manager',
        bindPassword: 'password'
    ],
    directoryAnonymousWithPool:[
        address: 'localhost',
        port: '33389',
        useSSL: false,
        trustSSLCert: true,
        followReferrals: true,
        useConnectionPool: true,
        initialConnections: 5,
        maxConnections: 50
    ]
]

directoryservice.dit = [
    'ou=people,dc=someu,dc=edu':[
        singular: 'person',
        plural: 'people',
        rdnAttribute: 'uid',
        source: 'directory',
        attributes: ['*', '+']
    ],
    'ou=departments,dc=someu,dc=edu':[
        singular: 'department',
        plural: 'departments',
        rdnAttribute: 'ou',
        source: 'directory'
    ],
    'ou=groups,dc=someu,dc=edu':[
        singular: 'group',
        plural: 'groups',
        rdnAttribute: 'cn',
        source: 'directory'
    ],
    'ou=personnes,dc=someu,dc=edu':[
        singular: 'personne',
        plural: 'personnes',
        rdnAttribute: 'uid',
        source: 'directory'
    ],
    'ou=accounts,dc=someu,dc=edu':[
        singular: 'account',
        plural: 'accounts',
        rdnAttribute: 'cn',
        source: 'ad'
    ],
    'ou=Accounts,dc=someu,dc=edu':[
        singular: 'accountFromPool',
        plural: 'accountsFromPool',
        rdnAttribute: 'cn',
        source: 'adWithPool'
    ],
    'ou=Economics,ou=accounts,dc=someu,dc=edu':[
        singular: 'economist',
        plural: 'economists',
        rdnAttribute: 'cn',
        source: 'ad'
    ],
    'ou=Statistics,ou=accounts,dc=someu,dc=edu':[
        singular: 'statistician',
        plural: 'statisticians',
        rdnAttribute: 'cn',
        source: 'ad'
    ],
    'ou=People,dc=someu,dc=edu':[
        singular: 'peep',
        plural: 'peeps',
        rdnAttribute: 'uid',
        source: 'directoryAnonymous',
        attributes: ['cn', 'sn', 'creatorsName']
    ],
    'ou=PEople,dc=someu,dc=edu':[
        singular: 'anonPeep',
        plural: 'anonPeeps',
        rdnAttribute: 'uid',
        source: 'directoryAnonymousWithPool',
        attributes: ['cn', 'sn', 'creatorsName']
    ]
]
