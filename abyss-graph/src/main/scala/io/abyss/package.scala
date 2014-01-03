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

package io

import akka.actor.{ActorLogging, Actor}
import akka.cluster.Cluster
import io.abyss.client._


/**
 * Created by cane, 09.07.13 21:41
 */
package object abyss {

    /**
     * Predefined number of shards, don't exceed 32768 because shard id is of type Short.
     * Number of shards also defines level of concurrency as each shard has instance of
     * ShardWorker actor.
     * TODO read from settings
     */
    final val NumberOfShards = 128


    /**
     * Returns short number representing id of shard which is designed to take care of given ID
     * @param id
     * @return
     */
    def shardId(id: String) = ( math.abs(id.hashCode) % NumberOfShards ).toShort



    trait AbyssActor extends Actor with ActorLogging {
		/**
		 * Cluster object.
		 */
		val cluster = Cluster (context.system)

		/**
		 * Roles of this node, taken from cluster configuration.
		 */
		val nodeRoles = cluster.selfRoles

		def config = context.system.settings.config

	}



	def clazzName(clazz: Class[_]): String = {
		if(clazz.getSimpleName.endsWith("$")) clazz.getSimpleName.dropRight(1) else clazz.getSimpleName
	}


	/**
	 * Returns map representation of given case class instance
	 * @param cc
	 * @return
	 */
	def ccToMap(cc: AnyRef) =
		(Map[String, Any]() /: cc.getClass.getDeclaredFields) {(a, f) =>
			f.setAccessible(true)
			a + (f.getName -> f.get(cc))
		}




    /**
     * Ask for ElementExists or NoSuchElement message
     * @param id Id of Vertex
     */
    case class VertexIntegrityRequired (id: String) extends Command

    /**
     * Ask for ElementExists or NoSuchElement message
     * @param id Id of Edge
     */
    case class EdgeIntegrityRequired (id: String) extends Command



    /**
     * Sent when edge has been created and remote shard must update indices held by vertex.
     * @param id
     */
    case class VertexInternalIndexUpdateRequired (id: String, from: String, to: String, bi: Boolean) extends Command


    /**
     * Returned when no element can be found
     */
    case object NoSuchElement


    /**
     * Returned when element exists in memory
     */
    case object ElementExists


}
