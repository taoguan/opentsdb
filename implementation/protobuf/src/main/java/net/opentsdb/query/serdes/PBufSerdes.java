// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.query.serdes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stumbleupon.async.Deferred;

import net.opentsdb.core.TSDB;
import net.opentsdb.core.TSDBPlugin;
import net.opentsdb.data.PBufQueryResult;
import net.opentsdb.data.PBufTimeSeriesId;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TypedIterator;
import net.opentsdb.data.pbuf.QueryResultPB;
import net.opentsdb.data.pbuf.TimeSeriesPB;
import net.opentsdb.data.pbuf.TimeSpecificationPB.TimeSpecification;
import net.opentsdb.data.pbuf.TimeStampPB.TimeStamp;
import net.opentsdb.exceptions.SerdesException;
import net.opentsdb.query.QueryContext;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.serdes.SerdesOptions;
import net.opentsdb.query.serdes.TimeSeriesSerdes;
import net.opentsdb.stats.Span;

/**
 * A serialization implementation that uses Google's Protobuf for 
 * de/serialization.
 * <p>
 * Various {@link PBufIteratorSerdes} implementations for specific
 * {@link TimeSeriesDataType}s can be registered via the 
 * {@link #registerSerdes(PBufIteratorSerdes)} call. Default 
 * implementations are provided for the core types.
 * 
 * @since 3.0
 */
public class PBufSerdes implements TimeSeriesSerdes, TSDBPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(PBufSerdes.class);
  
  /** The factory. */
  protected final PBufIteratorSerdesFactory factory;
  
  /**
   * Default ctor.
   */
  public PBufSerdes() {
    factory = new PBufIteratorSerdesFactory();
  }
  
  @Override
  public Deferred<Object> serialize(final QueryContext context, 
                                    final SerdesOptions options,
                                    final OutputStream stream, 
                                    final QueryResult result, 
                                    final Span span) {
    if (context == null) {
      throw new IllegalArgumentException("Context cannot be null.");
    }
    if (options == null) {
      throw new IllegalArgumentException("Options cannot be null.");
    }
    if (stream == null) {
      throw new IllegalArgumentException("Stream cannot be null.");
    }
    if (result == null) {
      throw new IllegalArgumentException("Query result cannot be null.");
    }
    final Span child;
    if (span != null) {
      child = span.newChild(getClass().getSimpleName() + ".serialize")
                  .start();
    } else {
      child = null;
    }
    
    try {
      serializeResult(context, options, result).writeTo(stream);
  
      if (child != null) {
        child.setSuccessTags().finish();
      }
      return Deferred.fromResult(null);
    } catch (RuntimeException e) {
      final RuntimeException ex = e instanceof SerdesException ? e : 
        new SerdesException("Unexpected execution deserializing data", e);
      if (child != null) {
        child.setErrorTags(ex).finish();
      }
      throw ex;
    } catch (IOException e) {
      final SerdesException ex = new SerdesException(
          "Unexpected exception serializing data", e);
      if (child != null) {
        child.setErrorTags(ex).finish();
      }
      throw ex;
    }
  }

  @Override
  public void deserialize(final SerdesOptions options, 
                          final InputStream stream, 
                          final QueryNode node, 
                          final Span span) {
    if (options == null) {
      throw new IllegalArgumentException("Options cannot be null.");
    }
    if (stream == null) {
      throw new IllegalArgumentException("Stream cannot be null.");
    }
    if (node == null) {
      throw new IllegalArgumentException("Query node cannot be null.");
    }
    
    final Span child;
    if (span != null) {
      child = span.newChild(getClass().getSimpleName() + ".deserialize")
                  .start();
    } else {
      child = null;
    }
    final PBufQueryResult result;
    try {
      result = new PBufQueryResult(factory, node, options, stream);
      if (child != null) {
        child.setSuccessTags().finish();
      }
      node.onNext(result);
    } catch (RuntimeException e) {
      final RuntimeException ex = e instanceof SerdesException ? e : 
        new SerdesException("Unexpected execution deserializing data", e);
      if (child != null) {
        child.setErrorTags(ex).finish();
      }
      node.onError(ex);
    }
  }

  /**
   * Registers the given serdes module with the factory, replacing any 
   * existing modules for the given type.
   * @param serdes A non-null serdes module.
   * @throws IllegalArgumentException if the serdes was null or it's type
   * was null.
   */
  public void registerSerdes(final PBufIteratorSerdes serdes) {
    factory.register(serdes);
  }
  
  /**
   * Serializes the result into a PBuf object.
   * @param context The non-null context to pull the query from.
   * @param options Options for serdes.
   * @param result The non-null result to serialize.
   * @return A non-null pbuf object.
   */
  public QueryResultPB.QueryResult serializeResult(
      final QueryContext context, 
      final SerdesOptions options,
      final QueryResult result) {
    final QueryResultPB.QueryResult.Builder result_builder = 
        QueryResultPB.QueryResult.newBuilder();
    if (result.timeSpecification() != null) {
      result_builder.setTimeSpecification(TimeSpecification.newBuilder()
          .setStart(TimeStamp.newBuilder()
              .setEpoch(result.timeSpecification().start().epoch())
              .setNanos(result.timeSpecification().start().nanos())
              .setZoneId(result.timeSpecification().timezone().toString()))
          .setEnd(TimeStamp.newBuilder()
              .setEpoch(result.timeSpecification().end().epoch())
              .setNanos(result.timeSpecification().end().nanos())
              .setZoneId(result.timeSpecification().timezone().toString()))
          .setInterval(result.timeSpecification().stringInterval())
          .setTimeZone(result.timeSpecification().timezone().toString()));
    }
    for (final TimeSeries ts : result.timeSeries()) {
      final TimeSeriesPB.TimeSeries.Builder ts_builder = 
          TimeSeriesPB.TimeSeries.newBuilder()
          .setId(PBufTimeSeriesId.newBuilder(
                ts.id())
              .build()
              .pbufID());
      
      for (final TypedIterator<TimeSeriesValue<? extends TimeSeriesDataType>> 
          iterator : ts.iterators()) {
        final PBufIteratorSerdes serdes = factory.serdesForType(iterator.getType());
        if (serdes == null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Skipping serialization of unknown type: " 
                + iterator.getType());
          }
          continue;
        }
        serdes.serialize(ts_builder, context, options, result, iterator);
      }
      
      result_builder.addTimeseries(ts_builder);
    }
    return result_builder.build();
  }
  
  @Override
  public String id() {
    return getClass().getSimpleName();
  }

  @Override
  public Deferred<Object> initialize(final TSDB tsdb) {
    return Deferred.fromResult(null);
  }

  @Override
  public Deferred<Object> shutdown() {
    return Deferred.fromResult(null);
  }

  @Override
  public String version() {
    return "3.0.0";
  }
  
}
