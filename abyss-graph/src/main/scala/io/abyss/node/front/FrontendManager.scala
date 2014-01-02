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

package io.abyss.node.front

import akka.actor.{Address, Terminated, Props}
import akka.routing.{Broadcast, RoundRobinRouter}
import io.abyss._
import io.abyss.node._


/*
 * Created by cane, 29.06.13 14:52
 * $Id: FrontendManager.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */


/**
 * Accessible as /user/node/front.
 *
 *
 */
class FrontendManager extends AbyssActor {


	// TODO consider consistent hashing routers in command


	val command = context.actorOf (Props[ CommandEndpoint ].withRouter (
		RoundRobinRouter (nrOfInstances = 1)), "command")

	val query = context.actorOf (Props[ QueryEndpoint ].withRouter (
		RoundRobinRouter (nrOfInstances = 2)), "query")

	val shardNodes = Array.ofDim[ Set[ Address ] ](NumberOfShards)
	0 to NumberOfShards - 1 foreach {
		i => shardNodes (i) = collection.immutable.Set.empty[ Address ]
	}


	//.withDefaultValue (collection.immutable.TreeSet.empty[ Address ])


	def receive = {

//		case msg: Command =>
//			command.forward (msg)
//
//		case msg: Query =>
//			query.forward (msg)

		case msg: ShardsOwned =>
			context watch sender
			msg.shards.foreach {
				sid =>
					shardNodes (sid) = shardNodes (sid) + msg.address
			}
			log.info("Broadcast: ShardNodeMap -> endpoints")
			val snm = Broadcast(ShardNodeMap (shardNodes))
			command ! snm
			query ! snm


		case msg: Terminated =>
			0 to NumberOfShards - 1 foreach {
				i =>
					if ( shardNodes (i).contains (msg.actor.path.address) ) shardNodes (i) = shardNodes (i) - msg.actor.path.address
			}

		case msg: AbyssClusterState =>
			val acs = Broadcast (msg)
			command ! acs
			query ! acs

		case msg =>
			log.warning ("Unknown message: {}", msg.toString)
	}

}
