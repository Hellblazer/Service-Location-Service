/** (C) Copyright 2010 Hal Hildebrand, All Rights Reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */
package com.hellblazer.slp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*******************************************************************************
 * Copyright (c) 2003, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

/**
 * RFC 1960-based Filter. Filter objects can be created by calling the
 * constructor with the desired filter string. A Filter object can be called
 * numerous times to determine if the match argument matches the filter string
 * that was used to create the Filter object.
 * 
 * <p>
 * The syntax of a filter string is the string representation of LDAP search
 * filters as defined in RFC 1960: <i>A String Representation of LDAP Search
 * Filters</i> (available at http://www.ietf.org/rfc/rfc1960.txt). It should be
 * noted that RFC 2254: <i>A String Representation of LDAP Search Filters</i>
 * (available at http://www.ietf.org/rfc/rfc2254.txt) supersedes RFC 1960 but
 * only adds extensible matching and is not applicable for this API.
 * 
 * <p>
 * The string representation of an LDAP search filter is defined by the
 * following grammar. It uses a prefix format.
 * 
 * <pre>
 *   &lt;filter&gt; ::= '(' &lt;filtercomp&gt; ')'
 *   &lt;filtercomp&gt; ::= &lt;and&gt; | &lt;or&gt; | &lt;not&gt; | &lt;item&gt;
 *   &lt;and&gt; ::= '&' &lt;filterlist&gt;
 *   &lt;or&gt; ::= '|' &lt;filterlist&gt;
 *   &lt;not&gt; ::= '!' &lt;filter&gt;
 *   &lt;filterlist&gt; ::= &lt;filter&gt; | &lt;filter&gt; &lt;filterlist&gt;
 *   &lt;item&gt; ::= &lt;simple&gt; | &lt;present&gt; | &lt;substring&gt;
 *   &lt;simple&gt; ::= &lt;attr&gt; &lt;filtertype&gt; &lt;value&gt;
 *   &lt;filtertype&gt; ::= &lt;equal&gt; | &lt;approx&gt; | &lt;greater&gt; | &lt;less&gt;
 *   &lt;equal&gt; ::= '='
 *   &lt;approx&gt; ::= '~='
 *   &lt;greater&gt; ::= '&gt;='
 *   &lt;less&gt; ::= '&lt;='
 *   &lt;present&gt; ::= &lt;attr&gt; '=*'
 *   &lt;substring&gt; ::= &lt;attr&gt; '=' &lt;initial&gt; &lt;any&gt; &lt;final&gt;
 *   &lt;initial&gt; ::= NULL | &lt;value&gt;
 *   &lt;any&gt; ::= '*' &lt;starval&gt;
 *   &lt;starval&gt; ::= NULL | &lt;value&gt; '*' &lt;starval&gt;
 *   &lt;final&gt; ::= NULL | &lt;value&gt;
 * </pre>
 * 
 * <code>&lt;attr&gt;</code> is a string representing an attribute, or key, in
 * the properties objects of the registered services. Attribute names are not
 * case sensitive; that is cn and CN both refer to the same attribute.
 * <code>&lt;value&gt;</code> is a string representing the value, or part of
 * one, of a key in the properties objects of the registered services. If a
 * <code>&lt;value&gt;</code> must contain one of the characters '<code>*</code>
 * ' or '<code>(</code>' or '<code>)</code>', these characters should be escaped
 * by preceding them with the backslash '<code>\</code>' character. Note that
 * although both the <code>&lt;substring&gt;</code> and
 * <code>&lt;present&gt;</code> productions can produce the
 * <code>'attr=*'</code> construct, this construct is used only to denote a
 * presence filter.
 * 
 * <p>
 * Examples of LDAP filters are:
 * 
 * <pre>
 *   &quot;(cn=Babs Jensen)&quot;
 *   &quot;(!(cn=Tim Howes))&quot;
 *   &quot;(&(&quot; + Constants.OBJECTCLASS + &quot;=Person)(|(sn=Jensen)(cn=Babs J*)))&quot;
 *   &quot;(o=univ*of*mich*)&quot;
 * </pre>
 * 
 * <p>
 * The approximate match (<code>~=</code>) is implementation specific but should
 * at least ignore case and white space differences. Optional are codes like
 * soundex or other smart "closeness" comparisons.
 * 
 * <p>
 * Comparison of values is not always straightforward. Strings are compared
 * differently than numbers and it is possible for a key to have multiple
 * values. Note that that he comparison is defined by the String comparison
 * rules.
 * 
 */

public class Filter implements Comparable<Filter> {
    /**
     * Parser class for OSGi filter strings. This class parses the complete
     * filter string and builds a tree of Filter objects rooted at the parent.
     */
    static class Parser {
        protected char[] filter;
        protected String filterstring;
        protected int    pos;

        protected Parser(String filterstring) {
            this.filterstring = filterstring;
            filter = filterstring.toCharArray();
            pos = 0;
        }

        protected void parse(Filter parent) throws InvalidSyntaxException {
            try {
                parse_filter(parent);
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new InvalidSyntaxException("Filter terminated abruptly: "
                                                 + filterstring);
            }

            if (pos != filter.length) {
                throw new InvalidSyntaxException(
                                                 "Filter trailing characters at ("
                                                         + pos + ")"
                                                         + filterstring);
            }
        }

        protected void parse_and(Filter parent) throws InvalidSyntaxException {
            skipWhiteSpace();

            if (filter[pos] != '(') {
                throw new InvalidSyntaxException(
                                                 "Filter missing left parentheses at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            ArrayList<Filter> operands = new ArrayList<Filter>(10);

            while (filter[pos] == '(') {
                Filter child = new Filter();
                parse_filter(child);
                operands.add(child);
            }

            int size = operands.size();

            Filter[] children = new Filter[size];

            operands.toArray(children);

            parent.setFilter(Filter.AND, null, children);
        }

        protected String parse_attr() throws InvalidSyntaxException {
            skipWhiteSpace();

            int begin = pos;
            int end = pos;

            char c = filter[pos];

            while ("~<>=()".indexOf(c) == -1) { //$NON-NLS-1$
                pos++;

                if (!Character.isWhitespace(c)) {
                    end = pos;
                }

                c = filter[pos];
            }

            int length = end - begin;

            if (length == 0) {
                throw new InvalidSyntaxException(
                                                 "Filter missing attribute at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            return new String(filter, begin, length);
        }

        protected void parse_filter(Filter parent)
                                                  throws InvalidSyntaxException {
            skipWhiteSpace();

            if (filter[pos] != '(') {
                throw new InvalidSyntaxException(
                                                 "Filter missing left parentheses at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            pos++;

            parse_filtercomp(parent);

            skipWhiteSpace();

            if (filter[pos] != ')') {
                throw new InvalidSyntaxException(
                                                 "Filter missing right parentheses at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            pos++;

            skipWhiteSpace();
        }

        protected void parse_filtercomp(Filter parent)
                                                      throws InvalidSyntaxException {
            skipWhiteSpace();

            char c = filter[pos];

            switch (c) {
                case '&': {
                    pos++;
                    parse_and(parent);
                    break;
                }
                case '|': {
                    pos++;
                    parse_or(parent);
                    break;
                }
                case '!': {
                    pos++;
                    parse_not(parent);
                    break;
                }
                default: {
                    parse_item(parent);
                    break;
                }
            }
        }

        protected void parse_item(Filter parent) throws InvalidSyntaxException {
            String attr = parse_attr();

            skipWhiteSpace();

            switch (filter[pos]) {
                case '~': {
                    if (filter[pos + 1] == '=') {
                        pos += 2;
                        parent.setFilter(Filter.APPROX, attr, parse_value());
                        return;
                    }
                    break;
                }
                case '>': {
                    if (filter[pos + 1] == '=') {
                        pos += 2;
                        parent.setFilter(Filter.GREATER, attr, parse_value());
                        return;
                    }
                    break;
                }
                case '<': {
                    if (filter[pos + 1] == '=') {
                        pos += 2;
                        parent.setFilter(Filter.LESS, attr, parse_value());
                        return;
                    }
                    break;
                }
                case '=': {
                    if (filter[pos + 1] == '*') {
                        int oldpos = pos;
                        pos += 2;
                        skipWhiteSpace();
                        if (filter[pos] == ')') {
                            parent.setFilter(Filter.PRESENT, attr, null);
                            return; /* present */
                        }
                        pos = oldpos;
                    }

                    pos++;
                    Object string = parse_substring();

                    if (string instanceof String) {
                        parent.setFilter(Filter.EQUAL, attr, string);
                    } else {
                        parent.setFilter(Filter.SUBSTRING, attr, string);
                    }

                    return;
                }
            }

            throw new InvalidSyntaxException(
                                             "Filter missing invalid operation at ("
                                                     + pos + ") "
                                                     + filterstring);
        }

        protected void parse_not(Filter parent) throws InvalidSyntaxException {
            skipWhiteSpace();

            if (filter[pos] != '(') {
                throw new InvalidSyntaxException(
                                                 "Filter missing left parentheses at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            Filter child = new Filter();
            parse_filter(child);

            parent.setFilter(Filter.NOT, null, child);
        }

        protected void parse_or(Filter parent) throws InvalidSyntaxException {
            skipWhiteSpace();

            if (filter[pos] != '(') {
                throw new InvalidSyntaxException(
                                                 "Filter missing left parentheses at ("
                                                         + pos + ") "
                                                         + filterstring);
            }

            ArrayList<Filter> operands = new ArrayList<Filter>(10);

            while (filter[pos] == '(') {
                Filter child = new Filter();
                parse_filter(child);
                operands.add(child);
            }

            int size = operands.size();

            Filter[] children = new Filter[size];

            operands.toArray(children);

            parent.setFilter(Filter.OR, null, children);
        }

        @SuppressWarnings("fallthrough")
        protected Object parse_substring() throws InvalidSyntaxException {
            StringBuffer sb = new StringBuffer(filter.length - pos);

            ArrayList<String> operands = new ArrayList<String>(10);

            parseloop: while (true) {
                char c = filter[pos];

                switch (c) {
                    case ')': {
                        if (sb.length() > 0) {
                            operands.add(sb.toString());
                        }

                        break parseloop;
                    }

                    case '(': {
                        throw new InvalidSyntaxException(
                                                         "Filter invalid value at ("
                                                                 + pos + ") "
                                                                 + filterstring);
                    }

                    case '*': {
                        if (sb.length() > 0) {
                            operands.add(sb.toString());
                        }

                        sb.setLength(0);

                        operands.add(null);
                        pos++;

                        break;
                    }

                    case '\\': {
                        pos++;
                        c = filter[pos];
                        /* fall through into default */
                    }

                    default: {
                        sb.append(c);
                        pos++;
                        break;
                    }
                }
            }

            int size = operands.size();

            if (size == 0) {
                throw new InvalidSyntaxException("Filter missing value at ("
                                                 + pos + ") " + filterstring);
            }

            if (size == 1) {
                Object single = operands.get(0);

                if (single != null) {
                    return single;
                }
            }

            String[] strings = new String[size];

            operands.toArray(strings);

            return strings;
        }

        @SuppressWarnings("fallthrough")
        protected String parse_value() throws InvalidSyntaxException {
            StringBuffer sb = new StringBuffer(filter.length - pos);

            parseloop: while (true) {
                char c = filter[pos];

                switch (c) {
                    case ')': {
                        break parseloop;
                    }

                    case '(': {
                        throw new InvalidSyntaxException(
                                                         "Filter invalid value at ("
                                                                 + pos + ") "
                                                                 + filterstring);
                    }

                    case '\\': {
                        pos++;
                        c = filter[pos];
                        /* fall through into default */
                    }

                    default: {
                        sb.append(c);
                        pos++;
                        break;
                    }
                }
            }

            if (sb.length() == 0) {
                throw new InvalidSyntaxException("Filter missing value at ("
                                                 + pos + ") " + filterstring);
            }

            return sb.toString();
        }

        protected void skipWhiteSpace() {
            int length = filter.length;

            while (pos < length && Character.isWhitespace(filter[pos])) {
                pos++;
            }
        }
    }

    static class SetAccessibleAction implements PrivilegedAction<Object> {
        private Constructor<?> constructor;

        public SetAccessibleAction(Constructor<?> constructor) {
            this.constructor = constructor;
        }

        @Override
        public Object run() {
            constructor.setAccessible(true);
            return null;
        }
    }

    private final static Logger       log             = LoggerFactory.getLogger(Filter.class);

    protected static final int        AND             = 7;

    protected static final int        APPROX          = 2;

    protected static final Class<?>[] constructorType = new Class<?>[] { String.class };

    protected static final int        EQUAL           = 1;

    protected static final int        GREATER         = 3;

    /* Protected fields and methods for the Filter implementation */

    protected static final int        LESS            = 4;
    protected static final int        NOT             = 9;
    protected static final int        OR              = 8;
    protected static final int        PRESENT         = 5;
    protected static final int        SUBSTRING       = 6;

    /**
     * Map a string for an APPROX (~=) comparison.
     * 
     * This implementation removes white spaces. This is the minimum
     * implementation allowed by the OSGi spec.
     * 
     * @param input
     *            Input string.
     * @return String ready for APPROX comparison.
     */
    protected static String approxString(String input) {
        boolean changed = false;
        char[] output = input.toCharArray();

        int length = output.length;

        int cursor = 0;
        for (int i = 0; i < length; i++) {
            char c = output[i];

            if (Character.isWhitespace(c)) {
                changed = true;
                continue;
            }

            output[cursor] = c;
            cursor++;
        }

        return changed ? new String(output, 0, cursor) : input;
    }

    /**
     * Encode the value string such that '(', '*', ')' and '\' are escaped.
     * 
     * @param value
     *            unencoded value string.
     * @return encoded value string.
     */
    protected static String encodeValue(String value) {
        boolean encoded = false;
        int inlen = value.length();
        int outlen = inlen << 1; /* inlen * 2 */

        char[] output = new char[outlen];
        value.getChars(0, inlen, output, inlen);

        int cursor = 0;
        for (int i = inlen; i < outlen; i++) {
            char c = output[i];

            switch (c) {
                case '(':
                case '*':
                case ')':
                case '\\': {
                    output[cursor] = '\\';
                    cursor++;
                    encoded = true;

                    break;
                }
            }

            output[cursor] = c;
            cursor++;
        }

        return encoded ? new String(output, 0, cursor) : value;
    }

    /** filter attribute or null if operation AND, OR or NOT */
    protected String  attr;
    /* normalized filter string for topLevel Filter object */
    protected String  filter;
    /** filter operation */
    protected int     operation;

    /* true if root Filter object */
    protected boolean topLevel;

    /** filter operands */
    protected Object  value;

    /**
     * Constructs a {@link Filter} object. This filter object may be used to
     * match a Map.
     * 
     * <p>
     * If the filter cannot be parsed, an {@link InvalidSyntaxException} will be
     * thrown with a human readable message where the filter became unparsable.
     * 
     * @param filter
     *            the filter string.
     * @exception InvalidSyntaxException
     *                If the filter parameter contains an invalid filter string
     *                that cannot be parsed.
     */
    public Filter(String filter) throws InvalidSyntaxException {
        topLevel = true;
        this.filter = filter;
        new Parser(filter).parse(this);
    }

    protected Filter() {
        topLevel = false;
    }

    /**
     * Compares this Filter object to another object.
     * 
     * @param obj
     *            the object to compare.
     * @return If the other object is a Filter object, then returns
     *         <code>this.toString().equals(obj.toString())</code>, otherwise
     *         <code>false</code>.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof Filter)) {
            return false;
        }

        return toString().equals(obj.toString());
    }

    /**
     * Returns all the attributes contained within this filter
     * 
     * @return all the attributes contained within this filter
     */
    public String[] getAttributes() {
        ArrayList<String> results = new ArrayList<String>();
        getAttributesInternal(results);
        return results.toArray(new String[results.size()]);
    }

    /**
     * Returns the hashCode for this Filter object.
     * 
     * @return The hashCode of the filter string, <i>i.e.</i>
     *         <code>this.toString().hashCode()</code>.
     */
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Filter using a Map. The Filter is executed using the Map's keys.
     * 
     * @param properties
     *            the map whose keys are used in the match.
     * @return <code>true</code> if the Map keys match this filter;
     *         <code>false</code> otherwise.
     */
    public boolean match(Map<String, String> properties) {
        if (properties != null) {
            properties = new HeaderMap(properties);
        }

        return match0(properties);
    }

    public boolean match(ServiceReference reference) {
        return match0(reference.properties);
    }

    /**
     * Filter with case sensitivity using a <tt>Map</tt> object. The Filter is
     * executed using the <tt>Map</tt> object's keys and values. The keys are
     * case sensitivley matched with the filter.
     * 
     * @param properties
     *            The <tt>Map</tt> object whose keys are used in the match.
     * 
     * @return <tt>true</tt> if the <tt>Map</tt> object's keys and values match
     *         this filter; <tt>false</tt> otherwise.
     * 
     * @since 1.3
     */
    public boolean matchCase(Map<String, String> properties) {
        return match0(properties);
    }

    /**
     * Returns this Filter object's filter string. The filter string is
     * normalized by removing whitespace which does not affect the meaning of
     * the filter.
     * 
     * @return filter string.
     */
    public String toString() {
        if (filter == null) {
            StringBuffer filter = new StringBuffer();
            filter.append('(');

            switch (operation) {
                case AND: {
                    filter.append('&');

                    Filter[] filters = (Filter[]) value;
                    int size = filters.length;

                    for (int i = 0; i < size; i++) {
                        filter.append(filters[i].toString());
                    }

                    break;
                }

                case OR: {
                    filter.append('|');

                    Filter[] filters = (Filter[]) value;
                    int size = filters.length;

                    for (int i = 0; i < size; i++) {
                        filter.append(filters[i].toString());
                    }

                    break;
                }

                case NOT: {
                    filter.append('!');
                    filter.append(value.toString());

                    break;
                }

                case SUBSTRING: {
                    filter.append(attr);
                    filter.append('=');

                    String[] substrings = (String[]) value;

                    int size = substrings.length;

                    for (int i = 0; i < size; i++) {
                        String substr = substrings[i];

                        if (substr == null) /* * */{
                            filter.append('*');
                        } else /* xxx */{
                            filter.append(encodeValue(substr));
                        }
                    }

                    break;
                }
                case EQUAL: {
                    filter.append(attr);
                    filter.append('=');
                    filter.append(encodeValue(value.toString()));

                    break;
                }
                case GREATER: {
                    filter.append(attr);
                    filter.append(">="); //$NON-NLS-1$
                    filter.append(encodeValue(value.toString()));

                    break;
                }
                case LESS: {
                    filter.append(attr);
                    filter.append("<="); //$NON-NLS-1$
                    filter.append(encodeValue(value.toString()));

                    break;
                }
                case APPROX: {
                    filter.append(attr);
                    filter.append("~="); //$NON-NLS-1$
                    filter.append(encodeValue(approxString(value.toString())));

                    break;
                }

                case PRESENT: {
                    filter.append(attr);
                    filter.append("=*"); //$NON-NLS-1$

                    break;
                }
            }

            filter.append(')');

            if (topLevel) /* only hold onto String object at toplevel */{
                this.filter = filter.toString();
            } else {
                return filter.toString();
            }
        }

        return filter;
    }

    private void getAttributesInternal(ArrayList<String> results) {
        if (value instanceof Filter[]) {
            Filter[] children = (Filter[]) value;
            for (Filter element : children) {
                element.getAttributesInternal(results);
            }
            return;
        } else if (value instanceof Filter) {
            // The NOT operation only has one child filter (bug 188075)
            ((Filter) value).getAttributesInternal(results);
            return;
        }
        if (attr != null) {
            results.add(attr);
        }
    }

    protected boolean compare(int operation, Object value1, Object value2) {
        if (value1 == null) {
            if (log.isTraceEnabled()) {
                log.trace("compare(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            return false;
        }

        if (value1 instanceof String) {
            return compare_String(operation, (String) value1, value2);
        }

        Class<?> clazz = value1.getClass();

        if (clazz.isArray()) {
            Class<?> type = clazz.getComponentType();

            if (type.isPrimitive()) {
                return compare_PrimitiveArray(operation, type, value1, value2);
            }
            return compare_ObjectArray(operation, (Object[]) value1, value2);
        }

        if (value1 instanceof Collection) {
            return compare_Collection(operation, (Collection<?>) value1, value2);
        }

        if (value1 instanceof Integer) {
            return compare_Integer(operation, ((Integer) value1).intValue(),
                                   value2);
        }

        if (value1 instanceof Long) {
            return compare_Long(operation, ((Long) value1).longValue(), value2);
        }

        if (value1 instanceof Byte) {
            return compare_Byte(operation, ((Byte) value1).byteValue(), value2);
        }

        if (value1 instanceof Short) {
            return compare_Short(operation, ((Short) value1).shortValue(),
                                 value2);
        }

        if (value1 instanceof Character) {
            return compare_Character(operation,
                                     ((Character) value1).charValue(), value2);
        }

        if (value1 instanceof Float) {
            return compare_Float(operation, ((Float) value1).floatValue(),
                                 value2);
        }

        if (value1 instanceof Double) {
            return compare_Double(operation, ((Double) value1).doubleValue(),
                                  value2);
        }

        if (value1 instanceof Boolean) {
            return compare_Boolean(operation,
                                   ((Boolean) value1).booleanValue(), value2);
        }

        if (value1 instanceof Comparable) {
            return compare_Comparable(operation, (Comparable<?>) value1, value2);
        }

        return compare_Unknown(operation, value1, value2); // RFC 59
    }

    protected boolean compare_Boolean(int operation, boolean boolval,
                                      Object value2) {
        boolean boolval2 = new Boolean(((String) value2).trim()).booleanValue();

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return boolval == boolval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return boolval == boolval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return boolval == boolval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return boolval == boolval2;
            }
        }

        return false;
    }

    protected boolean compare_Byte(int operation, byte byteval, Object value2) {
        byte byteval2 = Byte.parseByte(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return byteval == byteval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return byteval == byteval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return byteval >= byteval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return byteval <= byteval2;
            }
        }

        return false;
    }

    protected boolean compare_Character(int operation, char charval,
                                        Object value2) {
        char charval2 = ((String) value2).trim().charAt(0);

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return charval == charval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return Character.toLowerCase(charval) == Character.toLowerCase(charval2);
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return charval >= charval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return charval <= charval2;
            }
        }

        return false;
    }

    protected boolean compare_Collection(int operation,
                                         Collection<?> collection, Object value2) {
        Iterator<?> iterator = collection.iterator();

        while (iterator.hasNext()) {
            if (compare(operation, iterator.next(), value2)) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected boolean compare_Comparable(int operation, Comparable value1,
                                         Object value2) {
        Constructor constructor;

        try {
            constructor = value1.getClass().getConstructor(constructorType);
        } catch (NoSuchMethodException e) {
            return false;
        }
        try {
            if (!constructor.isAccessible()) {
                AccessController.doPrivileged(new SetAccessibleAction(
                                                                      constructor));
            }
            value2 = constructor.newInstance(new Object[] { ((String) value2).trim() });
        } catch (IllegalAccessException e) {
            return false;
        } catch (InvocationTargetException e) {
            return false;
        } catch (InstantiationException e) {
            return false;
        }

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.compareTo(value2) == 0;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.compareTo(value2) == 0;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.compareTo(value2) >= 0;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.compareTo(value2) <= 0;
            }
        }

        return false;
    }

    protected boolean compare_Double(int operation, double doubleval,
                                     Object value2) {
        double doubleval2 = Double.parseDouble(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return doubleval == doubleval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return doubleval == doubleval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return doubleval >= doubleval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return doubleval <= doubleval2;
            }
        }

        return false;
    }

    protected boolean compare_Float(int operation, float floatval, Object value2) {
        float floatval2 = Float.parseFloat(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return floatval == floatval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return floatval == floatval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return floatval >= floatval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return floatval <= floatval2;
            }
        }

        return false;
    }

    protected boolean compare_Integer(int operation, int intval, Object value2) {
        int intval2 = Integer.parseInt(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return intval == intval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return intval == intval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return intval >= intval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return intval <= intval2;
            }
        }

        return false;
    }

    protected boolean compare_Long(int operation, long longval, Object value2) {
        long longval2 = Long.parseLong(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return longval == longval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return longval == longval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return longval >= longval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return longval <= longval2;
            }
        }

        return false;
    }

    protected boolean compare_ObjectArray(int operation, Object[] array,
                                          Object value2) {
        int size = array.length;

        for (int i = 0; i < size; i++) {
            if (compare(operation, array[i], value2)) {
                return true;
            }
        }

        return false;
    }

    protected boolean compare_PrimitiveArray(int operation, Class<?> type,
                                             Object primarray, Object value2) {
        if (Integer.TYPE.isAssignableFrom(type)) {
            int[] array = (int[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Integer(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Long.TYPE.isAssignableFrom(type)) {
            long[] array = (long[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Long(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Byte.TYPE.isAssignableFrom(type)) {
            byte[] array = (byte[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Byte(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Short.TYPE.isAssignableFrom(type)) {
            short[] array = (short[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Short(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Character.TYPE.isAssignableFrom(type)) {
            char[] array = (char[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Character(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Float.TYPE.isAssignableFrom(type)) {
            float[] array = (float[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Float(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Double.TYPE.isAssignableFrom(type)) {
            double[] array = (double[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Double(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        if (Boolean.TYPE.isAssignableFrom(type)) {
            boolean[] array = (boolean[]) primarray;

            int size = array.length;

            for (int i = 0; i < size; i++) {
                if (compare_Boolean(operation, array[i], value2)) {
                    return true;
                }
            }

            return false;
        }

        return false;
    }

    protected boolean compare_Short(int operation, short shortval, Object value2) {
        short shortval2 = Short.parseShort(((String) value2).trim());

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return shortval == shortval2;
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return shortval == shortval2;
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return shortval >= shortval2;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return shortval <= shortval2;
            }
        }

        return false;
    }

    protected boolean compare_String(int operation, String string, Object value2) {
        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }

                String[] substrings = (String[]) value2;
                int pos = 0;

                int size = substrings.length;

                for (int i = 0; i < size; i++) {
                    String substr = substrings[i];

                    if (i + 1 < size) /* if this is not that last substr */{
                        if (substr == null) /* * */{
                            String substr2 = substrings[i + 1];

                            if (substr2 == null) {
                                continue; /* ignore first star */
                            }
                            /* *xxx */
                            if (log.isTraceEnabled()) {
                                log.trace("indexOf(\"" + substr2 + "\"," + pos + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            }
                            int index = string.indexOf(substr2, pos);
                            if (index == -1) {
                                return false;
                            }

                            pos = index + substr2.length();
                            if (i + 2 < size) {
                                i++;
                            }
                        } else /* xxx */{
                            int len = substr.length();

                            if (log.isTraceEnabled()) {
                                log.trace("regionMatches(" + pos + ",\"" + substr + "\")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            }
                            if (string.regionMatches(pos, substr, 0, len)) {
                                pos += len;
                            } else {
                                return false;
                            }
                        }
                    } else /* last substr */{
                        if (substr == null) /* * */{
                            return true;
                        }
                        if (log.isTraceEnabled()) {
                            log.trace("regionMatches(" + pos + "," + substr + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        }
                        return string.endsWith(substr);
                    }
                }

                return true;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return string.equals(value2);
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }

                string = approxString(string);
                String string2 = approxString((String) value2);

                return string.equalsIgnoreCase(string2);
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return string.compareTo((String) value2) >= 0;
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return string.compareTo((String) value2) <= 0;
            }
        }

        return false;
    }

    protected boolean compare_Unknown(int operation, Object value1,
                                      Object value2) { //RFC 59
        Constructor<?> constructor;
        try {
            constructor = value1.getClass().getConstructor(constructorType);
        } catch (NoSuchMethodException e) {
            if (log.isTraceEnabled()) {
                log.trace("Type not supported"); //$NON-NLS-1$
            }
            return false;
        }
        try {
            if (!constructor.isAccessible()) {
                AccessController.doPrivileged(new SetAccessibleAction(
                                                                      constructor));
            }
            value2 = constructor.newInstance(new Object[] { ((String) value2).trim() });
        } catch (IllegalAccessException e) {
            return false;
        } catch (InvocationTargetException e) {
            return false;
        } catch (InstantiationException e) {
            return false;
        }

        switch (operation) {
            case SUBSTRING: {
                if (log.isTraceEnabled()) {
                    log.trace("SUBSTRING(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return false;
            }
            case EQUAL: {
                if (log.isTraceEnabled()) {
                    log.trace("EQUAL(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.equals(value2);
            }
            case APPROX: {
                if (log.isTraceEnabled()) {
                    log.trace("APPROX(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.equals(value2);
            }
            case GREATER: {
                if (log.isTraceEnabled()) {
                    log.trace("GREATER(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.equals(value2);
            }
            case LESS: {
                if (log.isTraceEnabled()) {
                    log.trace("LESS(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return value1.equals(value2);
            }
        }

        return false;
    }

    /**
     * Internal match routine. Map parameter must support case-insensitive get.
     * 
     * @param properties
     *            A Map whose keys are used in the match.
     * @return If the Map's keys match the filter, return <code>true</code>.
     *         Otherwise, return <code>false</code>.
     */
    protected boolean match0(Map<String, String> properties) {
        switch (operation) {
            case AND: {
                Filter[] filters = (Filter[]) value;
                int size = filters.length;

                for (int i = 0; i < size; i++) {
                    if (!filters[i].match0(properties)) {
                        return false;
                    }
                }

                return true;
            }

            case OR: {
                Filter[] filters = (Filter[]) value;
                int size = filters.length;

                for (int i = 0; i < size; i++) {
                    if (filters[i].match0(properties)) {
                        return true;
                    }
                }

                return false;
            }

            case NOT: {
                Filter filter = (Filter) value;

                return !filter.match0(properties);
            }

            case SUBSTRING:
            case EQUAL:
            case GREATER:
            case LESS:
            case APPROX: {
                Object prop = properties == null ? null : properties.get(attr);

                return compare(operation, prop, value);
            }

            case PRESENT: {
                if (log.isTraceEnabled()) {
                    log.trace("PRESENT(" + attr + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }

                Object prop = properties == null ? null : properties.get(attr);

                return prop != null;
            }
        }

        return false;
    }

    protected void setFilter(int operation, String attr, Object value) {
        this.operation = operation;
        this.attr = attr;
        this.value = value;
    }

    public Set<String> findAssertions(String attribute) {
        Set<String> assertions = new HashSet<String>();
        switch (operation) {
            case AND: {
                Filter[] filters = (Filter[]) value;
                int size = filters.length;

                for (int i = 0; i < size; i++) {
                    assertions.addAll(filters[i].findAssertions(attribute));
                }

                break;
            }

            case NOT: {
                Filter filter = (Filter) value;
                assertions.removeAll(filter.findAssertions(attribute));
                break;
            }

            case OR: {
                Filter[] filters = (Filter[]) value;
                int size = filters.length;

                for (int i = 0; i < size; i++) {
                    assertions.addAll(filters[i].findAssertions(attribute));
                }

                break;
            }

            case SUBSTRING:
            case EQUAL:
            case GREATER:
            case LESS:
            case APPROX: {
                if (attr.equals(attribute)) {
                    assertions.add((String) value);
                }
                break;
            }

            case PRESENT: {
                if (attr.equals(attribute)) {
                    assertions.add((String) value);
                }
                break;
            }
            default:
        }

        return assertions;
    }

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(Filter o) {
        assert filter != null : String.format("Filter is null %s", this);
        assert o != null;
        assert o.filter != null;
        return filter.compareTo(o.filter);
    }
}