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

import java.util.concurrent.ConcurrentMap

import akka.actor._
import io.abyss.client.Command
import io.abyss.graph.model.GraphElement

import scala.collection.JavaConverters


/*
 * Created by cane, 18.06.13 13:51
 * $Id: ShardWorker.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */


sealed trait ShardState


case object Uninitialized extends ShardState


case object Restoring extends ShardState


case object Local extends ShardState


case object Moving extends ShardState


case object Cache extends ShardState


case object Remote extends ShardState


sealed trait ShardData


case object NoData extends ShardData


case class ShardWorkerData (remoteWorkers: Set[ ActorPath ], memberShardMap: AddressShardMap) extends ShardData


case class ShardElementUpdated (key: String, data: GraphElement)


case class ShardElementsUpdated (map: Map[ String, GraphElement ])


case class ShardTransferRequested (ref: ActorRef)


case class ShardTransferData (map: Map[ String, GraphElement ])


case object ShardTransferCompleted


/**
 *
 * @param sid Shard ID, number from 0 to 32767
 * @param memory Concurrent doMap from String to GraphElement
 */
class ShardWorker (val sid: Short, val memory: ConcurrentMap[ String, GraphElement ])
	extends FSM[ ShardState, ShardData ]
	with CommandProcessor {




	//	def save(v: Vertex)
	//	def restoreVertex(id: String): Vertex
	//	def restore


	startWith (Uninitialized, NoData)


	// state processing below

	// ---------------------------------------------------------------------------------------------------------------

	when (Uninitialized) {
		case Event (ReconfigureShards (clusterStart, msm, clusterState), NoData)
			if msm.shardsByAddress (cluster.selfAddress).contains (sid) =>

			// TODO with support for persistence go to RestoringFromDb state, which works almost same way as Restoring

			// when cluster starts then don't transfer shard's data, go to Local directly
			if ( clusterStart )
				goto (Local) using ShardWorkerData (
					remoteWorkers = remoteShardWorkers (msm),
					msm
				)
			else
				goto (Restoring) using ShardWorkerData (
					remoteWorkers = remoteShardWorkers (msm),
					msm
				)

		case Event (ReconfigureShards (clusterStart, msm, clusterState), NoData)
			if !msm.shardsByAddress (cluster.selfAddress).contains (sid) =>

			goto (Remote) using ShardWorkerData (
				remoteWorkers = remoteShardWorkers (msm),
				msm
			)
	}

	// restoring from other master replica

	when (Restoring) {


		case Event(msg: VerticesExists, swd: ShardWorkerData)
			if idsBelongsToMe(msg.ids) =>
			// TODO update memory with UnloadedGraphElement("V")
			// Node.memory.putIfAbsent(UnloadedGraphElement('V'))
			stay ()

		case Event(msg: EdgesExists, swd: ShardWorkerData)
			if idsBelongsToMe(msg.ids) =>
			// TODO update memory with UnloadedGraphElement("E")
			stay ()

		case Event (msg: ShardElementUpdated, swd: ShardWorkerData) =>
			// update memory with new data
			memory.replace (msg.key, msg.data)
			stay ()

		case Event (msg: ShardElementsUpdated, swd: ShardWorkerData) =>
			// update memory with new data
			memory.putAll (JavaConverters.mapAsJavaMapConverter (msg.map).asJava)
			stay ()

		case Event (ShardTransferCompleted, swd: ShardWorkerData) =>
			// kill transfer worker and go to Local mode
			sender ! Kill
			goto (Local) using swd

		// forward incoming messages to remote as it's able to process them
		case Event (msg, swd: ShardWorkerData) =>
			context.actorSelection (swd.remoteWorkers.head) tell(msg, sender)
			stay ()

	}


	// local mode

	when (Local) {

		case Event (msg: Command, swd: ShardWorkerData) =>
			processCommand (msg)
			stay ()

		case Event (msg: ShardElementUpdated, swd: ShardWorkerData) =>
			// update memory with new data
			memory.replace (msg.key, msg.data)
			// TODO send event to remotes, move to processor
			stay ()

		case Event (msg: ShardElementsUpdated, swd: ShardWorkerData) =>
			// update memory with new data
			memory.putAll (JavaConverters.mapAsJavaMapConverter (msg.map).asJava)
			// TODO send event to remotes, move to processor
			stay ()

		case Event (ReconfigureShards (clusterStart, msm, clusterState), swd: ShardWorkerData)
			if !msm.shardsByAddress (cluster.selfAddress).contains (sid) =>
			goto (Remote) using ShardWorkerData (
				remoteWorkers = remoteShardWorkers (msm),
				msm
			)

		case Event (msg: ShardTransferRequested, swd: ShardWorkerData) =>
			// TODO spawn transfer producer with msg.ref as destination
			context.actorOf (Props (new ShardTransferProducer (memory, msg.ref)))
			stay ()

		case Event (ShardTransferCompleted, swd: ShardWorkerData) =>
			// kill transfer worker
			sender ! Kill
			stay ()

		// TODO work with messages of other kinds

		case Event (msg, swd: ShardWorkerData) =>
			log.warning ("Unknown message while in 'Local' state: {}", msg.toString)
			stay ()

	}


	// remote mode

	when (Remote) {
		case Event (ReconfigureShards (clusterStart, msm, clusterState), swd: ShardWorkerData)
			if msm.shardsByAddress (cluster.selfAddress).contains (sid) =>
			goto (Local) using ShardWorkerData (
				remoteWorkers = remoteShardWorkers (msm),
				msm
			)

		case Event (msg, swd: ShardWorkerData) =>
			// TODO remote call, currently sends always to first actor from set
			// forward message to remote shard actor using own ID
			context.actorSelection (swd.remoteWorkers.head) tell(msg, sender)

			stay ()
	}


	// unexpected message according to current state

	whenUnhandled {
		// common code for all states

		case msg =>
			log.info ("Unhandled message: {}", msg.toString)
			stay ()
	}


	// when transitions between states occurs TODO no implementation here yet

	onTransition {
		case Uninitialized -> Restoring =>
			// TODO
			// determine if cluster was present at the time of call or not
			// if cluster has just been spawned, then no peers exists and no transfer is possible
			nextStateData match {
				case NoData =>
				// TODO some kind of error

				case data: ShardWorkerData =>
				//val ref = context.actorOf (Props (new ShardTransferConsumer (memory)))
				// inform peer shard that we want data
				//context.actorSelection (data.remoteWorkers.head) ! ShardTransferRequested (ref)
			}

		case Uninitialized -> Local =>

		case Local -> Remote =>
			stateData match {
				case NoData =>
				case data: ShardWorkerData =>
			}

			nextStateData match {
				case NoData =>
				case data: ShardWorkerData =>
			}
	}

	// ---------------------------------------------------------------------------------------------------------------

	// start FSM
	initialize ()


	/**
	 * Set of shard workers on remote systems, doing same job as this worker, but locally
	 */
	def remoteShardWorkers (memberShardMap: AddressShardMap) = {

		val workers = collection.mutable.HashSet.empty[ ActorPath ]

		0 to memberShardMap.arr (sid).size - 1 map {
			i =>
				if ( memberShardMap.arr (sid)(i).get != cluster.selfAddress )
					workers += RootActorPath (
						memberShardMap.arr (sid)(i).get) / "user" / "node" / "data" / "shard" / sid.toString
		}

		workers.toSet
	}


}


