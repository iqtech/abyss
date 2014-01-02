/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.abyss.node

import io.abyss.client._

import scala.collection.immutable
import akka.actor.Address
import scala.util.Random


/**
 * Created by cane, 10.07.13 16:10
 * $Id: package.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
package object data {


	val NumberOfQueryWorkersConfigKey = "abyss.data.query.number-of-workers"
	final val CollectionsConsistencyConfigKey = "abyss.data.consistency.collections"


	/**
	 * Maps cluster members and shards
	 * @param arr Array of size NumberOfShards, contains Arrays with Member holding this shard
	 * @param map Map Member - Set of shard IDs
	 */
	case class AddressShardMap (arr: Array[ Array[ Option[ Address ] ] ],
							   map: Map[ Address, immutable.SortedSet[ Short ] ]) {
		def addressesForId (id: String): Array[ Option[ Address ] ] = arr (shardId (id))
		def randomAddressForId (id: String): Option[ Address ] = {
			val a = arr (shardId (id))
			a(Random.nextInt() % a.size)
		}
		def shardsByAddress(address: Address): Set[Short] = map(address)
	}


	/**
	 * Starts shards reconfiguration
	 * @param clusterStart
	 * @param addressShardMap
	 * @param clusterState
	 */
	case class ReconfigureShards (clusterStart: Boolean,
								  //ownedByMe: immutable.SortedSet[ Short ],
								  //ownedByOthers: immutable.SortedSet[ Short ],
								  addressShardMap: AddressShardMap,
								  clusterState: AbyssClusterState)

	/**
	 * Information that vertices with given ids exists
	 * @param ids Identifiers of vertices
	 */
	case class VerticesExists(ids: Array[String])

	/**
	 * Information that edges with given ids exists
	 * @param ids Identifiers of edges
	 */
	case class EdgesExists(ids: Array[String])

}
