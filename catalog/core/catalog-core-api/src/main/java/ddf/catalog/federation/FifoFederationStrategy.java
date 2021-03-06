/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package ddf.catalog.federation;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;
import org.slf4j.ext.XLogger;

import ddf.catalog.federation.AbstractFederationStrategy;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryResponseImpl;
import ddf.catalog.operation.SourceResponse;
import ddf.catalog.source.Source;

/**
 * The Class {@code FifoFederationStrategy} represents a First In First Out (FIFO)
 * federation strategy that returns results in the order they are received.
 * This means that the first results received by this strategy are the first results
 * sent back to the client.
 * </br><b>WARNING - This class does not support the timeout parameter from the {@code Query}<b/>
 * 
 * 
 * @author ddf.isgs@lmco.com
 * @deprecated - As of DDF v2.1.0.ALPHA1. FifoFederationStrategy has been moved to /ddf/trunk/catalog/federation, federation-impl is now a separate bundle containing federation strategy implementations. 
 * @see SortedFederationStrategy
 */
public class FifoFederationStrategy extends AbstractFederationStrategy{
    
    private static XLogger logger = new XLogger( LoggerFactory.getLogger( FifoFederationStrategy.class ) );

    /**
     * Instantiates a {@code FifoFederationStrategy} with the provided {@link ExecutorService}.
     * 
     * @param queryExecutorService the {@link ExecutorService} for queries
     */
    public FifoFederationStrategy( ExecutorService queryExecutorService ) {
        super( queryExecutorService );
    }

    @Override
    protected Runnable createMonitor( final ExecutorService pool,  final Map<Source, Future<SourceResponse>> futures, 
            final QueryResponseImpl returnResults, final Query query ) {
        
        return new FifoQueryMonitor( pool, futures, returnResults, query );
    }

    /**
     * Gets the time remaining before the timeout on a query
     * 
     * @param deadline
     *            - the deadline for the timeout to occur
     * @return the time remaining prior to the timeout
     */
    /*private long getTimeRemaining( long deadline ) {
        long timeleft;
        if ( System.currentTimeMillis() > deadline ) {
            timeleft = 0;
        } else {
            timeleft = deadline - System.currentTimeMillis();
        }
        return timeleft;
    }*/
    
    private class FifoQueryMonitor implements Runnable{
        
        private QueryResponseImpl returnResults;
        private Map<Source, Future<SourceResponse>> futures;
        private Query query;
        private ExecutorService pool;
        private AtomicInteger total = new AtomicInteger();
        private AtomicInteger sites = new AtomicInteger();

        public FifoQueryMonitor( ExecutorService pool, Map<Source, Future<SourceResponse>> futuress,
                QueryResponseImpl returnResults, Query query ) {
            this.pool = pool;
            this.returnResults = returnResults;
            this.query = query;
            this.futures = futuress;
        }     

        private void addToGrandTotal( int addition ) {
            total.addAndGet( addition );
        }

        private int updateSites( int addition ) {
            return sites.addAndGet( addition );
        }

        @Override
        public void run() {
            for ( final Map.Entry<Source, Future<SourceResponse>> entry : futures.entrySet() ) {
                updateSites( 1 );
                pool.submit( new SourceQueryThread( entry.getValue(), returnResults ) );
            }
        }
        
        private class SourceQueryThread implements Runnable {
            private int lastKnownGrandTotal = 0;
            private long sentTotal = 0;
            Future<SourceResponse> curFuture = null;
            QueryResponseImpl returnResults = null;
            
            public SourceQueryThread( Future<SourceResponse> curFuture, QueryResponseImpl returnResults ){
                this.curFuture = curFuture;
                this.returnResults = returnResults;
            }

            @Override
            public void run() {
                SourceResponse queryResponse = null;
                try {
                    queryResponse = curFuture.get();
                } catch ( Exception e ) {
                    logger.warn( "FederatedSite.query() never returned" );
                } 
                if ( queryResponse != null ) {
                    //boolean hasTotals = false;
                	int pageSize = query.getPageSize() > 0 ? query.getPageSize() : Integer.MAX_VALUE;

                    //long deadline = System.currentTimeMillis() + query.getTimeoutMillis();
                    //boolean noTimeout = ( query.getTimeoutMillis() < 1 );

                    // Check if we have hit the maximum number
                    // of results
                    if ( sentTotal >= pageSize ) {
                        logger.debug( "Received max number of results so ending polling" );
                        
                    }else{
                        returnResults.addResults( queryResponse.getResults(), false );
                        sentTotal += queryResponse.getHits();
                    }                                 
                }

                addToGrandTotal( lastKnownGrandTotal );
                logger.debug( "adding grand total from this site to grand total from all sites: " + total.get() );
                if ( updateSites( -1 ) == 0 ) {
                    // all done, send the Terminator.
                    logger.debug( "sending terminator for fifo federation strategy." );
                    returnResults.closeResultQueue();
                }
            }
        }
    }
    
}
