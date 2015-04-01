/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.ndp.transport.http.util;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.ndp.runtime.internal.StandardSessions;
import org.neo4j.ndp.transport.http.HttpTransport;
import org.neo4j.ndp.transport.http.SessionRegistry;
import org.neo4j.ndp.runtime.Session;
import org.neo4j.test.TestGraphDatabaseFactory;

public class Neo4jWithHttp implements TestRule
{
    private HttpTransport listener;
    private final LifeSupport life = new LifeSupport();

    public String baseURL()
    {
        return listener.baseURL();
    }

    @Override
    public Statement apply( final Statement statement, Description description )
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                final GraphDatabaseService gdb = new TestGraphDatabaseFactory().newImpermanentDatabase();
                final GraphDatabaseAPI api = ((GraphDatabaseAPI) gdb);
                StringLogger log = api.getDependencyResolver().resolveDependency( Logging.class )
                        .getMessagesLog( Session.class );

                listener = life.add( new HttpTransport(
                        "localhost", 8080,
                        new SessionRegistry( life.add(new StandardSessions( api, log )) ),
                        log) );
                life.start();
                try
                {
                    statement.evaluate();
                }
                finally
                {
                    life.stop();
                    gdb.shutdown();
                }
            }
        };
    }

    public HTTP.Builder client()
    {
        return HTTP.withBaseUri( "http://localhost:8080" );
    }
}
