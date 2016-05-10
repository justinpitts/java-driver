/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core.querybuilder;

import com.datastax.driver.core.CodecRegistry;
import com.datastax.driver.core.TableMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * A built INSERT statement.
 */
public class Insert extends BuiltStatement {

    private final String table;
    private final List<Object> names = new ArrayList<Object>();
    private final List<Object> values = new ArrayList<Object>();
    private final Options usings;
    private boolean ifNotExists;
    private String json;

    Insert(String keyspace, String table) {
        super(keyspace);
        this.table = table;
        this.usings = new Options(this);
    }

    Insert(TableMetadata table) {
        super(table);
        this.table = escapeId(table.getName());
        this.usings = new Options(this);
    }

    @Override
    StringBuilder buildQueryString(List<Object> variables, CodecRegistry codecRegistry) {
        StringBuilder builder = new StringBuilder();

        builder.append("INSERT INTO ");
        if (keyspace != null)
            Utils.appendName(keyspace, builder).append('.');
        Utils.appendName(table, builder);

        builder.append(" ");
        if (json != null) {
            builder.append("JSON '");
            builder.append(json);
            builder.append("'");
        } else {
            builder.append("(");
            Utils.joinAndAppendNames(builder, codecRegistry, ",", names);
            builder.append(") VALUES (");
            Utils.joinAndAppendValues(builder, codecRegistry, ",", values, variables);
            builder.append(')');
        }

        if (ifNotExists)
            builder.append(" IF NOT EXISTS");

        if (!usings.usings.isEmpty()) {
            builder.append(" USING ");
            Utils.joinAndAppend(builder, codecRegistry, " AND ", usings.usings, variables);
        }
        return builder;
    }

    /**
     * Adds a column/value pair to the values inserted by this INSERT statement.
     *
     * @param name  the name of the column to insert/update.
     * @param value the value to insert/update for {@code name}.
     * @return this INSERT statement.
     * @throws IllegalStateException if this method is called and the {@link #json(String)}
     *                               method has been called before, because it's not possible
     *                               to mix {@code INSERT JSON} syntax with regular {@code INSERT} syntax.
     */
    public Insert value(String name, Object value) {
        checkState(json == null, "Cannot mix INSERT JSON syntax with regular INSERT syntax");
        names.add(name);
        values.add(value);
        checkForBindMarkers(value);
        if (!hasNonIdempotentOps() && !Utils.isIdempotent(value))
            this.setNonIdempotentOps();
        maybeAddRoutingKey(name, value);
        return this;
    }

    /**
     * Adds multiple column/value pairs to the values inserted by this INSERT statement.
     *
     * @param names  a list of column names to insert/update.
     * @param values a list of values to insert/update. The {@code i}th
     *               value in {@code values} will be inserted for the {@code i}th column
     *               in {@code names}.
     * @return this INSERT statement.
     * @throws IllegalArgumentException if {@code names.length != values.length}.
     * @throws IllegalStateException    if this method is called and the {@link #json(String)}
     *                                  method has been called before, because it's not possible
     *                                  to mix {@code INSERT JSON} syntax with regular {@code INSERT} syntax.
     */
    public Insert values(String[] names, Object[] values) {
        return values(Arrays.asList(names), Arrays.asList(values));
    }

    /**
     * Adds multiple column/value pairs to the values inserted by this INSERT statement.
     *
     * @param names  a list of column names to insert/update.
     * @param values a list of values to insert/update. The {@code i}th
     *               value in {@code values} will be inserted for the {@code i}th column
     *               in {@code names}.
     * @return this INSERT statement.
     * @throws IllegalArgumentException if {@code names.size() != values.size()}.
     * @throws IllegalStateException    if this method is called and the {@link #json(String)}
     *                                  method has been called before, because it's not possible
     *                                  to mix {@code INSERT JSON} syntax with regular {@code INSERT} syntax.
     */
    public Insert values(List<String> names, List<Object> values) {
        if (names.size() != values.size())
            throw new IllegalArgumentException(String.format("Got %d names but %d values", names.size(), values.size()));
        checkState(json == null, "Cannot mix INSERT JSON syntax with regular INSERT syntax");
        this.names.addAll(names);
        this.values.addAll(values);
        for (int i = 0; i < names.size(); i++) {
            Object value = values.get(i);
            checkForBindMarkers(value);
            maybeAddRoutingKey(names.get(i), value);
            if (!hasNonIdempotentOps() && !Utils.isIdempotent(value))
                this.setNonIdempotentOps();
        }
        return this;
    }

    /**
     * Inserts the provided JSON string, using the {@code INSERT INTO ... JSON} syntax introduced
     * in Cassandra 2.2.
     * <p>
     * With INSERT statements, the new {@code JSON} keyword can be used to enable inserting a
     * JSON encoded map as a single row.
     * <p>
     * <strong>The provided JSON string will be appended to the query string as is.
     * It should NOT be surrounded by single quotes.</strong>
     * Its format should generally match that returned by a
     * {@code SELECT JSON} statement on the same table.
     * <p>
     * Note that it is not possible to insert function calls nor bind markers in the JSON string.
     * <h3>Case-sensitive column names</h3>
     * Users are required to handle case-sensitive column names
     * by surrounding them with double quotes.
     * <p>
     * For example, to insert into a table with two columns named “myKey” and “value”,
     * you would do the following:
     * <pre>
     * insertInto("mytable").json("{\"\\\"myKey\\\"\": 0, \"value\": 0}");
     * </pre>
     * This will produce the following CQL:
     * <pre>
     * INSERT INTO mytable JSON '{"\"myKey\"": 0, "value": 0}';
     * </pre>
     * <h3>Escaping quotes in column values</h3>
     * Double quotes should be escaped with a backslash, but single quotes should be escaped in
     * the CQL manner, i.e. by another single quote. For example, the column value
     * {@code foo"'bar} should be inserted in the JSON string
     * as {@code "foo\"''bar"}.
     * <p>
     * <h3>Null values and tombstones</h3>
     * Any columns which are omitted from the JSON string will be defaulted to a {@code NULL} value
     * (which will result in a tombstone being created).
     *
     * @param json the JSON string.
     * @return this INSERT statement.
     * @throws IllegalStateException if this method is called and any of the {@code value} or {@code values}
     *                               methods have been called before, because it's not possible
     *                               to mix {@code INSERT JSON} syntax with regular {@code INSERT} syntax.
     * @see <a href="http://cassandra.apache.org/doc/cql3/CQL-2.2.html#json">JSON Support for CQL</a>
     * @see <a href="http://www.datastax.com/dev/blog/whats-new-in-cassandra-2-2-json-support">JSON Support in Cassandra 2.2</a>
     * @see <a href="https://docs.datastax.com/en/cql/3.3/cql/cql_using/useInsertJSON.html">Inserting JSON data</a>
     */
    public Insert json(String json) {
        checkState(values.isEmpty() && names.isEmpty(), "Cannot mix INSERT JSON syntax with regular INSERT syntax");
        this.json = json;
        return this;
    }

    /**
     * Adds a new options for this INSERT statement.
     *
     * @param using the option to add.
     * @return the options of this INSERT statement.
     */
    public Options using(Using using) {
        return usings.and(using);
    }

    /**
     * Returns the options for this INSERT statement.
     * <p/>
     * Chain this with {@link Options#and(Using)} to add options.
     *
     * @return the options of this INSERT statement.
     */
    public Options using() {
        return usings;
    }

    /**
     * Sets the 'IF NOT EXISTS' option for this INSERT statement.
     * <p/>
     * An insert with that option will not succeed unless the row does not
     * exist at the time the insertion is executed. The existence check and
     * insertions are done transactionally in the sense that if multiple
     * clients attempt to create a given row with this option, then at most one
     * may succeed.
     * <p/>
     * Please keep in mind that using this option has a non negligible
     * performance impact and should be avoided when possible.
     *
     * @return this INSERT statement.
     */
    public Insert ifNotExists() {
        this.ifNotExists = true;
        return this;
    }

    /**
     * The options of an INSERT statement.
     */
    public static class Options extends BuiltStatement.ForwardingStatement<Insert> {

        private final List<Using> usings = new ArrayList<Using>();

        Options(Insert st) {
            super(st);
        }

        /**
         * Adds the provided option.
         *
         * @param using an INSERT option.
         * @return this {@code Options} object.
         */
        public Options and(Using using) {
            usings.add(using);
            checkForBindMarkers(using);
            return this;
        }

        /**
         * Adds a column/value pair to the values inserted by this INSERT statement.
         *
         * @param name  the name of the column to insert/update.
         * @param value the value to insert/update for {@code name}.
         * @return the INSERT statement those options are part of.
         */
        public Insert value(String name, Object value) {
            return statement.value(name, value);
        }

        /**
         * Adds multiple column/value pairs to the values inserted by this INSERT statement.
         *
         * @param names  a list of column names to insert/update.
         * @param values a list of values to insert/update. The {@code i}th
         *               value in {@code values} will be inserted for the {@code i}th column
         *               in {@code names}.
         * @return the INSERT statement those options are part of.
         * @throws IllegalArgumentException if {@code names.length != values.length}.
         */
        public Insert values(String[] names, Object[] values) {
            return statement.values(names, values);
        }
    }
}
