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

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ReadOnlyEntry
import com.unboundid.ldap.sdk.SearchResultEntry

/**
 * When DirectoryService returns a result set, it wraps each result object in
 * a DirectoryServiceEntry object. This class allows you to interact with an
 * UnboundID SearchResultEntry as if it were a writable Entry. In fact, when
 * it is constructed with a SearchResultEntry, it duplicates the entry which
 * results in an UnboundID Entry object, which you can update. The updating
 * is done by overriding {@code propertyMissing(String name, value)}.
 *
 * @author Lucas Rockwell
 */
class DirectoryServiceEntry implements Serializable {
    private static final long serialVersionUID = -148355487038013198L;

    /**
     * The original searchResultEntry.
     *
     * Gets reset to a copy of entry on cleanupAfterSave()
     */
    ReadOnlyEntry searchResultEntry

    /**
     * UnboundID Entry object.
     *
     * Gets reset to searchResultEntry.duplicate() by discard().
     */
    Entry entry

    /**
     * The base DN which was used for searching for this entry. It is
     * important that we have this because we need it when we save or
     * delete the entry, i.e., it is critical that we map this entry to its
     * source.
     */
    String baseDN

    /**
     * Simple map for keeping track of whether or not an attribute has changed.
     * We keep this up-to-date as well as modifications because it is a lot
     * easier to inspect this for changes when calling isDirty(attribute) than
     * looping through the modifications array.
     *
     * Gets reset by discard() and cleanupAfterSave().
     */
    private Map mods = [:]

    /**
     * Simple map for keeping track of any errors which might occur when using
     * the object. For now, if directoryService.save(this) throws an error, it
     * will populate errors['save'] with the reason.
     *
     * Gets reset by discard() and cleanupAfterSave().
     *
     * Note: A future version of this class will have an errors object that
     * implements the Spring Errors interface, so the error handling will
     * change.
     */
    Map errors = [:]

    /**
     * Holds the actual directory modifications.
     *
     * Gets reset by discard() and cleanupAfterSave().
     */
    private List<Modification> modifications = []

    /**
     * Constructs a new DirectoryServiceEntry object by passing in
     * an UnboundID SearchResultEntry. It then takes the SearchResultEntry
     * and calls {@code duplicate()} on it so that we end up with
     * an Entry object which and then be modified.
     *
     * @param searchResultEntry     The search result entry that will
     * be used in this object.
     * @param baseDN                The baseDN which was used to get the
     * connection to the appropriate directory.
     */
    DirectoryServiceEntry(SearchResultEntry searchResultEntry,
        String baseDN) {
        this.searchResultEntry = searchResultEntry
        this.entry             = searchResultEntry.duplicate()
        this.baseDN            = baseDN
    }

    /**
     * Constructs a new DirectoryServiceEntry object from the passed in Map
     * of {@code attrs}, singular {@code ditItem} value (so that it knows how
     * to find the dit config), and {@code directoryService} object so that it
     * can get the config without having to create a new DirectoryService
     * instance.
     *
     * The returned object can then be used just like any DirectoryServiceEntry
     * object, modifying, it, etc., but the real purpose for it is to then add
     * it to the server to which its baseDN belongs.
     *
     * The {@code attrs} must contain the RDN attribute for the {@code ditItem}.
     * If this RDN value is a list, then the actual RDN of the DN will be the
     * first item in the list.
     *
     * Either the {@code attrs} or the found dit map must contain an attribute
     * called {@code objectClass} and must be a list of values. If there are
     * {@code objectClass} values in both the dit map and the passed in
     * {@code attrs}, the values from the latter will be used.
     *
     * @param attrs             Map of attributes and values.
     * @param ditItem           String value of a {@code singular} item in the
     *                          dit map in the config.
     * @param directoryService  A valid DirectoryService object.
     * @throws Exception if any number of conditions are not met (as noted in
     * in the summary above).
     */
    DirectoryServiceEntry(Map attrs, String ditItem,
        DirectoryService directoryService) throws Exception {
        def ditMap = directoryService.config.directoryservice.dit.find {
            it.value.singular == ditItem
        }
        
        if (!ditMap) {
            def msg = "Could not find the dit map that corresponds to the " +
                "passed in '${ditItem}' ditItem."
            throw new Exception(msg)
        }

        def values = ditMap.value

        if (!values.objectClass && !attrs.objectClass) {
            def msg = "Could not find 'objectClass' values in either the " +
                "passed in attributes or the dit map."
            throw new Exception(msg)
        }

        def objectClass = attrs.objectClass ?: values.objectClass
        if (objectClass.getClass() == java.lang.String) {
            def msg = 'objectClass values must be a list.'
            throw new Exception(msg)
        }

        if (!attrs[values.rdnAttribute]) {
            def msg = 'Could not find the RDN attribute ' +
                "'${values.rdnAttribute}' in the passed in attributes."
            throw new Exception(msg)
        }

        def rdnAttr = attrs[values.rdnAttribute]
        if (rdnAttr.getClass() != java.lang.String) {
            rdnAttr = rdnAttr[0]
        }
        def dn      = "${values.rdnAttribute}=${rdnAttr}," + ditMap.key
        def entry   = new Entry(dn)
        attrs.each {key, value ->
            entry.addAttribute(key, value)
        }
        if (!entry.getAttributeValue('objectClass')) {
            entry.addAttribute('objectClass', values.objectClass)
        }

        this.searchResultEntry = new ReadOnlyEntry(entry)
        this.entry             = entry.duplicate()
        this.baseDN            = ditMap.key
    }

    /**
     * Constructs a new DirectoryServiceEntry object from the passed in Entry
     * {@code entry}, and sets the {@code baseDN} to be the parent DN of the
     * DN of the entry.
     *
     * The returned object can then be used just like any DirectoryServiceEntry
     * object, modifying, it, etc., but the real purpose for it is to then add
     * it to the server to which its baseDN belongs.
     *
     * @param entry             Entry object that will be the base object.
     * @param directoryService  A valid DirectoryService object
     * @throws Exception if the DN of the passed in entry cannot be parsed as
     * a true DN, or if the parent DN of the entry cannot be found in the dit
     * part of the config.
     */
    DirectoryServiceEntry(Entry entry, DirectoryService directoryService)
        throws Exception {

        def parentDN = entry.getParentDNString()
        def ditMap   = directoryService.config.directoryservice.dit[parentDN]

        if (!ditMap) {
            def msg = "Could not find the dit map that corresponds to the " +
                "parent DN '${parentDN}' of the passed in entry."
            throw new Exception(msg)
        }

        this.searchResultEntry = new ReadOnlyEntry(entry)
        this.entry             = entry.duplicate()
        this.baseDN            = parentDN
    }

    /**
     * Any property which is called on this class is passed as a
     * getAttributeValue() method call to the {@code entry} object which is
     * set on this class. If the {@code entry} object is not set, the method
     * throws a {@code MissingPropertyException}.
     *
     * This method detects if the attribute name is 'dn', and if it is, it
     * calls {@code entry.getDN()} instead of {@code entry.getAttributeValue()}.
     */
    def propertyMissing(String name) {
        if (entry) {
            if (name == 'dn') {
                return entry.getDN()
            }
            else {
                return entry.getAttributeValue(name)
            }
        }
        else {
            throw new MissingPropertyException(name)
        }
    }

    /**
     * This propertyMissing gets invoked when a value is supplied with the
     * property name, so we override it so that we can set values in the
     * entry object. We also set the mods map, and update the modifications
     * List.
     */
    def propertyMissing(String name, value) {
        if (entry) {
            mods[name] = value
            if (value) {
                if (value instanceof String || value instanceof String[]) {
                    entry.setAttribute(name, value)
                }
                else {
                    entry.setAttribute(name,
                        value.toArray(new String[value.size()]))
                }
            }
            else {
                if (entry.getAttributeValue(name)) {
                    entry.removeAttribute(name)
                }
            }
            // Now update the modifications list
            /*
            if (value instanceof String || value instanceof String[]) {
                updateModifications(name, value)
            }
            else {
                updateModifications(name,
                    value?.toArray(new String[value ? value.size(): 1]))
            }
            */
            updateModifications()
        }
    }

    /**
     * Intercepts the following methods:
     *
     * <ul>
     *   <li>getAttributeValues()</li>
     *   <li>&lt;attribute name&gt;Values()</li>
     *   <li>&lt;attribute name&gt;AsDate()</li>
     *   <li>&lt;attribute name&gt;AsBoolean()</li>
     *   <li>&lt;attribute name&gt;AsLong()</li>
     *   <li>dn()</li>
     * </ul>
     *
     * These need to be called as methods instead of properties because with
     * LDAP you never know if someone is going to name their attribute with one
     * of these names at the end, so it is safest to force them to be methods.
     */
    def methodMissing(String name, args) {
        if (entry) {
            if (name.matches(/^getAttributeValues?$/)) {
                return entry.invokeMethod(name, args)
            }
            else if (name.matches(/^(\w+)Values?$/)) {
                return entry.getAttributeValues(name - 'Values')
            }
            else if (name.matches(/^(\w+)?AsDate$/)) {
                return entry.getAttributeValueAsDate(name - 'AsDate')
            }
            else if (name.matches(/^(\w+)?AsBoolean$/)) {
                return entry.getAttributeValueAsBoolean(name - 'AsBoolean')
            }
            else if (name.matches(/^(\w+)?AsLong$/)) {
                return entry.getAttributeValueAsLong(name - 'AsLong')
            }
            else {
                if (name == 'dn') {
                    return entry.getDN()
                }
                else {
                    return entry.getAttributeValue(name)
                }
            }
        }
        else {
            throw new MissingMethodException(name, delegate, args)
        }
    }

    /**
     * Discards any changes made to the object. Reinitializes both the
     * {@code mods} map and the {@code modifications} array, and sets
     * the entry as a duplicate of the searchResultEntry.
     */
    def discard() {
        mods = [:]
        errors = [:]
        modifications = []
        entry = searchResultEntry.duplicate()
    }

    /**
     * Similar to discard(), but instead of resetting the entry, it recreates
     * the searchResultEntry from the entry object, as we want the state of
     * this object to be what was just saved to the directory.
     */
    def cleanupAfterSave() {
        mods = [:]
        errors = [:]
        modifications = []
        searchResultEntry =
            new ReadOnlyEntry(entry)
    }

    /**
     * Checks to see if this object has been modified. You can also pass in
     * an optional attribute name to check to see if just that one attribute
     * has been modified.
     *
     * Note: This method is named isDirty to match GORM.
     *
     * @param attribute         Optional attribute name to check to see if it
     * has been modified.
     * @return {@code true} if this object (or the supplied attribute) has been
     * modified, and {@code false} otherwise.
     */
    def isDirty(attribute=null) {
        if (mods) {
            if (attribute) {
                return mods[attribute] ? true : false
            }
            return true
        }
        return false
    }

    /**
     * Updates the {@code modifications} list by calling the UnboundID
     * Entry.diff() method against the {@code searchResultEntry} and the
     * modified {@code entry} objects.
     * @param Boolean reversable    If <code>false</code>, it uses the
     * "REPLACE" method for doing modifications. If <code>true</code>
     * (which is the default), it does the "DELETE"/"ADD" method for doing
     * modifications.
     *
     * This method also sets {@code byteForByte} as <code>true</code> by
     * default, so that case matters for stings. If you do not want it set
     * as <code>true</code>, you will need to call the method with both the
     * {@code reversable} argument as well as the {@code byteForByte} argument.
     *
     * Even though setting properties always calls this method with the
     * default of <code>true</code>, this method can always be called with
     * <code>false</code> as the argument. Even if the modifications have
     * already been set, they will get recalculated.
     *
     * @param reversable    Whether or not the diff should use DELETE/ADD
     *                      instead of REPLACE. Default is <code>true</code>.
     * @param byteForByte   Whether or not the diff should do a byte-for-byte
     *                      comparison of the attribute changes. Default is
     *                      <code>true</code>.
     */
    public void updateModifications(reversable=true, byteForByte=true) {
        modifications = Entry.diff((Entry)searchResultEntry, entry, true,
            reversable, byteForByte)
    }

}
