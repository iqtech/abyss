/*
 * Copyright 2013-2014 IQ TECH <http://www.iqtech.pl>
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

package io.abyss.node.data

import akka.actor.Props
import akka.actor.RootActorPath
import scala.Some
import io.abyss.AbyssActor
import io.abyss.node._
import io.abyss.node.data.persistence.PersistenceManager


/**
 * Created by cane, 29.06.13 14:47
 * $Id: DataManager.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class DataManager extends AbyssActor {

	val InitialGraphMemorySize: Int = 1000000
	//private val memory = new ConcurrentHashMap[ String, GraphElement ](InitialGraphMemorySize)
	def memory = Node.memory


	/**
	 * Shard manager
	 */
	val shard = context.actorOf(Props(new ShardManager(memory)), "shard")

	/**
	 * Query manager
	 */
	val query = context.actorOf(Props(new QueryManager(memory)), "query")

	/**
	 * Persistence manager
	 */
	val persistence = context.actorOf(Props[PersistenceManager], "persistence")

	var abyssClusterState: Option[AbyssClusterState] = None

	// TODO .....

	def receive = {

//		case msg: QueryMany =>
//			log.info("Forwarding: QueryMany -> query manager")
//			query forward msg

		case msg: ShardsOwned =>
			log.info("Sending ShardsOwned to fronts")
			persistence ! msg
			abyssClusterState.get.frontNodes.map(m=>context.actorSelection(RootActorPath(m.address) / "user" / "node" / "front")).foreach {
				front => front ! msg
			}



		case acs: AbyssClusterState =>
			abyssClusterState = Some(acs)
			shard ! acs
			query ! acs

		case msg =>
			log.warning("Uncaught message: {}", msg.toString)
	}
}
