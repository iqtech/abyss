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

package io.abyss.node.front

import akka.actor.{Address, ActorPath}
import akka.cluster.Member
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.RootActorPath
import io.abyss._
import io.abyss.client._
import io.abyss.node.{ShardNodeMap, AbyssClusterState}

/*
 * Created by cane, 09.07.13 21:33
 * $Id: CommandEndpoint.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */


/**
 * Forwards received messages to correct shard workers if possible, if not (in case of batch commands) - forwards
 * messages to shards manager.
 */
class CommandEndpoint extends AbyssActor {


	private var backNodes = collection.immutable.SortedSet.empty[ Member ]

	private var shardManagers = Set.empty[ ActorPath ]

	private var shardNodes: Array[ Set[ Address ] ] = null

	implicit val defaultTimeout = Timeout (2 seconds)



	def receive = {

		case msg: Command =>
			routeCommandToShard(msg)

		case msg: CommandSeq =>
			log warning "Not implemented"

		// cluster state has been distributed
		case acs: AbyssClusterState =>
			backNodes = acs.backNodes
			shardManagers = backNodes.map (m => RootActorPath (m.address) / "user" / "node" / "data" / "shard").toSet

		// shards were calculated
		case msg: ShardNodeMap =>
			shardNodes = msg.arr

		case msg =>
			log.warning ("Uncaught message: {}", msg)

	}


	private def routeCommandToShard(msg: Command): Unit = {
        import scala.concurrent.ExecutionContext.Implicits.global
		// send message to shard with correct ID and wait for response, which then return to sender, all asynchronously

		// TODO don't use head only, but don't forget about sequence of command - should be forwarded to same shards
		val shard = context.actorSelection (shardManagers.head / shardId (msg.id).toString)


		// TODO construct envelope with sender attached and reply from backend directly
		val askedBy = sender

		ask(shard, msg).mapTo[ Any ] foreach {
			res =>
				log.info ("Command result received: %s" format res.toString)
				askedBy ! res
		}

	}
}
