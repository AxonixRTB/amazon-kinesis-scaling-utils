/**
 * Amazon Kinesis Scaling Utility
 *
 * Copyright 2014, Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.amazonaws.services.kinesis.scaling;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.DescribeStreamSummaryRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamSummaryResult;
import com.amazonaws.services.kinesis.model.LimitExceededException;
import com.amazonaws.services.kinesis.model.ListShardsRequest;
import com.amazonaws.services.kinesis.model.ListShardsResult;
import com.amazonaws.services.kinesis.model.ResourceInUseException;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.StreamDescriptionSummary;
import com.amazonaws.services.kinesis.scaling.StreamScaler.SortOrder;
import com.amazonaws.services.sns.AmazonSNSClient;

public class StreamScalingUtils {
	private static final Log LOG = LogFactory.getLog(StreamScalingUtils.class);

	public static final int DESCRIBE_RETRIES = 10;

	public static final int MODIFY_RETRIES = 10;

	// retry timeout set to 100ms as API's will potentially throttle > 10/sec
	public static final int RETRY_TIMEOUT_MS = 100;

	// rounding scale for BigInteger and BigDecimal comparisons
	public static final int PCT_COMPARISON_SCALE = 10;

	public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_DOWN;

	private static interface KinesisOperation {
		public Object run(AmazonKinesis client);
	}

	/**
	 * Method to do a fuzzy comparison between two doubles, so that we can make
	 * generalisations about allocation of keyspace to shards. For example, when we
	 * have a stream of 3 shards, we'll have shards of 33, 33, and 34% of the
	 * keyspace - these must all be treated as equal
	 *
	 * @param a
	 * @param b
	 * @return
	 */
	public static int softCompare(double a, double b) {
		// allow variation by 1 order of magnitude greater than the comparison
		// scale
		final BigDecimal acceptedVariation = BigDecimal.valueOf(1d)
				.divide(BigDecimal.valueOf(10d).pow(PCT_COMPARISON_SCALE - 1));

		BigDecimal first = new BigDecimal(a).setScale(PCT_COMPARISON_SCALE, ROUNDING_MODE);
		BigDecimal second = new BigDecimal(b).setScale(PCT_COMPARISON_SCALE, ROUNDING_MODE);

		BigDecimal variation = first.subtract(second).abs();

		// if the variation of the two values is within the accepted variation,
		// then we return 'equal'
		if (variation.compareTo(acceptedVariation) < 0) {
			return 0;
		} else {
			return first.compareTo(second);
		}
	}

	/**
	 * Wait for a Stream to become available or transition to the indicated status
	 *
	 * @param streamName
	 * @param status
	 * @throws Exception
	 */
	public static void waitForStreamStatus(AmazonKinesis kinesisClient, String streamName, String status)
			throws Exception {
		boolean ok = false;
		String streamStatus;
		// stream mutation takes around 30 seconds, so we'll start with 20 as
		// a timeout
		int waitTimeout = 20000;
		do {
			streamStatus = getStreamStatus(kinesisClient, streamName);
			if (!streamStatus.equals(status)) {
				Thread.sleep(waitTimeout);
				// reduce the wait timeout from the initial wait time
				waitTimeout = 1000;
			} else {
				ok = true;
			}
		} while (!ok);
	}

	/**
	 * Get the status of a Stream
	 *
	 * @param streamName
	 * @return
	 */
	protected static String getStreamStatus(AmazonKinesis kinesisClient, String streamName) throws Exception {

		return describeStream(kinesisClient, streamName).getStreamStatus();
	}

	public static StreamDescriptionSummary describeStream(final AmazonKinesis kinesisClient, final String streamName)
			throws Exception {
		KinesisOperation describe = new KinesisOperation() {
			public Object run(AmazonKinesis client) {
				DescribeStreamSummaryResult result = client
						.describeStreamSummary(new DescribeStreamSummaryRequest().withStreamName(streamName));

				return result.getStreamDescriptionSummary();
			}
		};
		return (StreamDescriptionSummary) doOperation(kinesisClient, describe, streamName, DESCRIBE_RETRIES, false);
	}

	public static List<Shard> listShards(final AmazonKinesis kinesisClient, final String streamName,
			final String shardIdStart) throws Exception {
		LOG.debug(String.format("Listing Stream %s from Shard %s", streamName, shardIdStart));

		KinesisOperation describe = new KinesisOperation() {
			public Object run(AmazonKinesis client) {
				ListShardsRequest req = new ListShardsRequest().withStreamName(streamName);
				boolean hasMoreResults = true;
				List<Shard> shards = new ArrayList<>();

				while (hasMoreResults) {
					if (shardIdStart != null) {
						req.withExclusiveStartShardId(shardIdStart);
					}
					ListShardsResult result = client.listShards(req);
					shards.addAll(result.getShards());

					if (result.getNextToken() == null) {
						hasMoreResults = false;
					} else {
						req = new ListShardsRequest().withNextToken(result.getNextToken());
					}

				}
				return shards;
			}
		};
		return (List<Shard>) doOperation(kinesisClient, describe, streamName, DESCRIBE_RETRIES, false);
	}

	public static Shard getShard(final AmazonKinesis kinesisClient, final String streamName, final String shardIdStart)
			throws Exception {
		LOG.debug(String.format("Getting Shard %s for Stream %s", shardIdStart, streamName));

		KinesisOperation describe = new KinesisOperation() {
			public Object run(AmazonKinesis client) {
				// reduce the shardIdStart by 1 as the API uses it as an exclusive start key not
				// a filter
				String shardIdToQuery = new BigDecimal(shardIdStart).subtract(new BigDecimal("1")).toString();
				ListShardsRequest req = new ListShardsRequest().withStreamName(streamName)
						.withExclusiveStartShardId(shardIdToQuery);
				ListShardsResult result = client.listShards(req);

				return result.getShards().get(0);
			}
		};
		return (Shard) doOperation(kinesisClient, describe, streamName, DESCRIBE_RETRIES, false);
	}

	public static void splitShard(final AmazonKinesis kinesisClient, final String streamName, final String shardId,
			final BigInteger targetHash, final boolean waitForActive) throws Exception {
		LOG.debug(String.format("Splitting Shard %s at %s", shardId, targetHash.toString()));

		KinesisOperation split = new KinesisOperation() {
			public Object run(AmazonKinesis client) {
				client.splitShard(streamName, shardId, targetHash.toString());

				return null;
			}
		};
		doOperation(kinesisClient, split, streamName, MODIFY_RETRIES, waitForActive);
	}

	public static void mergeShards(final AmazonKinesis kinesisClient, final String streamName,
			final ShardHashInfo lowerShard, final ShardHashInfo higherShard, final boolean waitForActive)
			throws Exception {
		LOG.debug(String.format("Merging Shard %s and %s", lowerShard, higherShard));

		KinesisOperation merge = new KinesisOperation() {
			public Object run(AmazonKinesis client) {
				client.mergeShards(streamName, lowerShard.getShardId(), higherShard.getShardId());

				return null;
			}
		};
		doOperation(kinesisClient, merge, streamName, MODIFY_RETRIES, waitForActive);
	}

	private static Object doOperation(AmazonKinesis kinesisClient, KinesisOperation operation, String streamName,
			int retries, boolean waitForActive) throws Exception {
		boolean done = false;
		int attempts = 0;
		Object result = null;
		do {
			attempts++;
			try {
				result = operation.run(kinesisClient);

				if (waitForActive) {
					waitForStreamStatus(kinesisClient, streamName, "ACTIVE");
				}
				done = true;
			} catch (ResourceInUseException e) {
				// thrown when the Shard is mutating - wait until we are able to
				// do the modification or ResourceNotFoundException is thrown
				Thread.sleep(1000);
			} catch (LimitExceededException lee) {
				// API Throttling
				LOG.warn(String.format("LimitExceededException for Stream %s", streamName));

				Thread.sleep(getTimeoutDuration(attempts));
			}
		} while (!done && attempts < retries);

		if (!done) {
			throw new Exception(String.format("Unable to Complete Kinesis Operation after %s Retries", retries));
		} else {
			return result;
		}
	}

	// calculate an exponential backoff based on the attempt count
	private static final long getTimeoutDuration(int attemptCount) {
		return new Double(Math.pow(2, attemptCount) * RETRY_TIMEOUT_MS).longValue();
	}

	private static final int compareShardsByStartHash(Shard o1, Shard o2) {
		return new BigInteger(o1.getHashKeyRange().getStartingHashKey())
				.compareTo(new BigInteger(o2.getHashKeyRange().getStartingHashKey()));
	}

	public static int getOpenShardCount(AmazonKinesisClient kinesisClient, String streamName) throws Exception {
		return StreamScalingUtils.describeStream(kinesisClient, streamName).getOpenShardCount();
	}

	/**
	 * Get a list of all Open shards ordered by their start hash
	 *
	 * @param streamName
	 * @return A Map of only Open Shards indexed by the Shard ID
	 */
	public static Map<String, ShardHashInfo> getOpenShards(AmazonKinesisClient kinesisClient, String streamName,
			String lastShardId) throws Exception {
		return getOpenShards(kinesisClient, streamName, SortOrder.ASCENDING, lastShardId);
	}

	public static ShardHashInfo getOpenShard(AmazonKinesisClient kinesisClient, String streamName, String shardId)
			throws Exception {
		Shard s = getShard(kinesisClient, streamName, shardId);

		if (!s.getShardId().equals(shardId)) {
			throw new Exception(String.format("Shard %s not found in Stream %s", shardId, streamName));
		} else {
			return new ShardHashInfo(streamName, s);
		}
	}

	public static Map<String, ShardHashInfo> getOpenShards(AmazonKinesisClient kinesisClient, String streamName,
			SortOrder sortOrder, String lastShardId) throws Exception {
		Collection<String> openShardNames = new ArrayList<>();
		Map<String, ShardHashInfo> shardMap = new LinkedHashMap<>();

		// load all the open shards on the Stream and sort if required
		for (Shard shard : listShards(kinesisClient, streamName, lastShardId)) {
			openShardNames.add(shard.getShardId());
			shardMap.put(shard.getShardId(), new ShardHashInfo(streamName, shard));

			// remove this Shard's parents from the set of active shards - they
			// are now closed and cannot be modified or written to
			if (shard.getParentShardId() != null) {
				openShardNames.remove(shard.getParentShardId());
				shardMap.remove(shard.getParentShardId());
			}
			if (shard.getAdjacentParentShardId() != null) {
				openShardNames.remove(shard.getAdjacentParentShardId());
				shardMap.remove(shard.getAdjacentParentShardId());
			}
		}

		// create a List of Open shards for sorting
		List<Shard> sortShards = new ArrayList<>();
		for (String s : openShardNames) {
			// paranoid null check in case we get a null map entry
			if (s != null) {
				sortShards.add(shardMap.get(s).getShard());
			}
		}

		if (sortOrder.equals(SortOrder.ASCENDING)) {
			// sort the list into lowest start hash order
			Collections.sort(sortShards, new Comparator<Shard>() {
				public int compare(Shard o1, Shard o2) {
					return compareShardsByStartHash(o1, o2);
				}
			});
		} else if (sortOrder.equals(SortOrder.DESCENDING)) {
			// sort the list into highest start hash order
			Collections.sort(sortShards, new Comparator<Shard>() {
				public int compare(Shard o1, Shard o2) {
					return compareShardsByStartHash(o1, o2) * -1;
				}
			});
		} // else we were supplied a NONE sort order so no sorting

		// build the Shard map into the correct order
		shardMap.clear();
		for (Shard s : sortShards) {
			shardMap.put(s.getShardId(), new ShardHashInfo(streamName, s));
		}

		return shardMap;
	}

	public static void sendNotification(AmazonSNSClient snsClient, String notificationARN, String subject,
			String message) {
		snsClient.publish(notificationARN, message, subject);
	}
}
