// Since we are testing failover above, we need another
// Map of source details for the InMemoryServer. This is
// just made up for our tests, and is not part of the API
// for this project.
directoryservice.sources = [
    directory:[
        address: 'localhost',
        port: '33389',
        useSSL: false,
        trustSSLCert: true,
        bindDN: 'cn=Directory Manager',
        bindPassword: 'password',
        followReferrals: false
    ],
    ad:[
        address: 'localhost',
        port: '33268',
        useSSL: false,
        trustSSLCert: true,
        bindDN: 'cn=AD Manager',
        bindPassword: 'password',
        followReferrals: false
    ],
    adWithPool:[
        address: 'localhost',
        port: '33269',
        useSSL: false,
        trustSSLCert: true,
        bindDN: 'cn=AD Manager',
        bindPassword: 'password',
        followReferrals: false
    ],
    forListenTest:[
        address: 'localhost',
        port: '33390',
        useSSL: false,
        trustSSLCert: true,
        bindDN: 'cn=Directory Manager',
        bindPassword: 'password',
        followReferrals: false
    ],
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
