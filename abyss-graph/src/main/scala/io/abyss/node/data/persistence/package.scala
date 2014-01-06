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


/**
 * Persistence package.
 *
 * Created by cane, 8/16/13 11:47 AM
 * $Id: package.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
package object persistence {


	// INTERFACE

	//	/**
	//	 * Each implemented persistence provider must provide this interface
	//	 */
	//	trait PersistenceProvider {
	//
	//		def create (key: String, data: VertexState)
	//
	//		def create (key: String, data: EdgeState)
	//
	//		def readVertex (key: String): VertexState
	//
	//		def readEdge (key: String): EdgeState
	//
	//		def update (key: String, data: VertexState)
	//
	//		def update (key: String, data: EdgeState)
	//
	//		def delete (key: String)
	//
	//	}


	// CONFIGURATION

	trait PersistenceProviderConfig {
		val name: String
	}


	case class GlobalPersistenceConfig (writeBehind: Boolean,
										writeBehindIntervalMillis: Option[ Int ],
										modelFactoryActorPath: String = "/user/node/data/model/factory",
										provider: String = "cassandra")


	case class CassandraPersistenceProviderConfig (name: String,
												   keySpace: String,
												   replicationFactor: Int,
												   nodes: Array[ String ]) extends PersistenceProviderConfig


	/**
	 *
	 * @param global Global settings
	 * @param provider When set it defines storage backend
	 */
	case class AbyssPersistenceConfig (global: GlobalPersistenceConfig,
									   provider: PersistenceProviderConfig)


	/**
	 * Default persistence configuration:
	 * - uses Cassandra@localhost:9160
	 * - writeBehind is enabled and started every 500 ms
	 */
	object AbyssDefaultPersistenceConfig {

		def apply (): AbyssPersistenceConfig = {
			val global = GlobalPersistenceConfig (writeBehind = true, writeBehindIntervalMillis = Some (500))
			val storage = CassandraPersistenceProviderConfig ("cassandra", "abyss-default", 1, Array ("localhost:9160"))

			AbyssPersistenceConfig (global, storage)
		}

	}


	//	import WeekDay._
	//	def isWorkingDay(d: WeekDay) = ! (d == Sat || d == Sun)
	//	WeekDay.values filter isWorkingDay foreach println


	/**
	 * Each type may be differently processed by Cassandra. Here is wrapper for class and consistency levels.
	 * @param collection
	 * @param consistencyRead
	 * @param consistencyWrite
	 */
	case class CollectionConsistencyConfig (collection: String,
											consistencyRead: AbyssConsistencyLevel.AbyssConsistencyLevel = AbyssConsistencyLevel.Any,
											consistencyWrite: AbyssConsistencyLevel.AbyssConsistencyLevel = AbyssConsistencyLevel.All)


	/**
	 * Sent to persistence manager when vertex changed.
	 * @param id
	 */
	case class DirtyVertex (id: String)


	/**
	 * Sent to persistence manager when edge changed.
	 * @param id
	 */
	case class DirtyEdge (id: String)


    // state and data definition for persistence provider

    trait PersistenceProviderState
    case object Initializing

    trait PersistenceProviderData
    case object NoData

}
