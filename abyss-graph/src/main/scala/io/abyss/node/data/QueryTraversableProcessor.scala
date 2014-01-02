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

package io.abyss.node.data

import akka.actor.Address
import akka.actor.RootActorPath
import io.abyss.client._
import io.abyss._
import io.abyss.graph.model._
import java.util.concurrent.ConcurrentMap
import scala.Some


/*
 * Created by cane, 15.07.13 16:44
 * $Id: QueryTraversableProcessor.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */

// QueryTraversableProcessor needs some messages and structures

/**
 * Map job to process by this processor
 * @param id
 * @param sender
 * @param step
 */
case class TraverseMapJob(id: String,
						  step: Int,
						  sender: String
							 )


/**
 * Reduce job to process by this processor
 * @param id
 * @param feasible
 * @param sender
 * @param step
 * @param leaf Exists when feasible,
 */
case class TraverseReduceJob(id: String,
							 step: Int,
							 sender: String,
							 feasible: Boolean,
							 leaf: Option[String] = None
								)


/**
 * Item of search tree, items are constructed on any node and links only by id.
 * @param id
 * @param step
 * @param parent
 * @param status
 * @param numberOfActiveChildren
 * @param numberOfFeasiblePaths
 */
case class TraverseTreeItem(id: String,
							step: Int,
							parent: String,
							status: Byte,
							numberOfActiveChildren: Int,
							numberOfFeasiblePaths: Int)


/**
 * Object for TraverseTreeItem
 */
object TraverseTreeItem {
	val Active = 0.toByte
	val Feasible = 1.toByte
	val Failed = 2.toByte
}


/**
 * Defines traversable query processor.
 */
trait QueryTraversableProcessor extends AbyssActor {

	/**
	 * Main memory of node.
	 */
	val memory: ConcurrentMap[String, GraphElement]

	/**
	 * Array of addresses of nodes which are responsible for processing IDs from shard definded by index in array.
	 */
	val shardMap: Array[Address]

	/**
	 * Set to true when traversing finished
	 */
	private var traversingFinished = false

	/**
	 * Currently processed query
	 */
	protected var currentQuery = null.asInstanceOf[QueryTraversable]

	/**
	 * Parts of search tree arranged in map where key is (step, elementId) tuple
	 */
	private val traversedByStepMap = collection.mutable.HashMap
		.empty[(Int, String), TraverseTreeItem]

	/**
	 * Collection of TraverseMapJob objects, which must be processed.
	 */
	private val mapJobs = collection.mutable.Queue.empty[TraverseMapJob]

	/**
	 * Map of collections of map jobs to send to other nodes. Addresses of nodes are keys in map.
	 */
	private val externalMapJobs = collection.mutable.HashMap.empty[Address, collection.mutable.Queue[TraverseMapJob]]
		.withDefaultValue (collection.mutable.Queue.empty[TraverseMapJob])

	/**
	 * Collection of TraverseReduceJob objects, which must be processed.
	 */
	private val reduceJobs = collection.mutable.Queue.empty[TraverseReduceJob]

	/**
	 * Map of collections of reduce jobs to send to other nodes. Addresses of nodes are keys in map.
	 */
	private val externalReduceJobs = collection.mutable.HashMap.empty[Address, collection.mutable.Queue[TraverseReduceJob]]
		.withDefaultValue (collection.mutable.Queue.empty[TraverseReduceJob])

	/**
	 * Map jobs waiting for reduce phase on given element in given step
	 */
	private val awaitingMapJobs = collection.mutable.HashMap.empty[(Int, String), collection.mutable.Queue[TraverseMapJob]]
		.withDefaultValue (collection.mutable.Queue.empty[TraverseMapJob])


	protected val feasibleElements = collection.mutable.TreeSet.empty[String]


	/**
	 * True if last step has been made, check it every time reduce jobs are processed, when true - traversing is over
	 * and results may be returned.
	 * @return
	 */
	def finished = traversingFinished


	/**
	 * Returns address of node responsible for processing element (by it's ID)
	 * @param id
	 * @return
	 */
	private def addressById(id: String) = shardMap (shardId (id))


	/**
	 * Starts this processor at given element.
	 * @param id
	 */
	protected def startTraversingAt(id: String): Unit = {
		val job = TraverseMapJob (id = id, 0, null)
		enqueueMapJob (job)
		mapJobsReceived (Array ())
	}

	/**
	 * Puts batch of messages into map job queue and starts doMap()
	 * @param msg
	 */
	protected def mapJobsReceived(msg: Array[TraverseMapJob]): Unit = {
		mapJobs ++= msg
		processMapJobs ()
		processReduceJobs ()
		sendExternalJobs ()
	}


	protected def reduceJobsReceived(msg: Array[TraverseReduceJob]): Unit = {
		reduceJobs ++= msg
		processReduceJobs ()
		sendExternalJobs ()
	}


	/**
	 * Sends jobs which must be - according to their id - processed by external nodes.
	 */
	// TODO make single array of map and reduce jobs per address
	// TODO send many small batches rather than one big - read batch size from config - abyss.remote.batch.size
	private def sendExternalJobs(): Unit = {
		externalMapJobs foreach {
			t =>
				val path = RootActorPath (t._1) / "user" / "node" / "data" / "query" / currentQuery.id
				log.info ("Sending external map jobs: {} to {}", t._2, t._1)

				context.actorSelection (path) ! t._2.toArray
		}

		externalMapJobs.clear ()

		externalReduceJobs foreach {
			t =>
				val path = RootActorPath (t._1) / "user" / "node" / "data" / "query" / currentQuery.id
				context.actorSelection (path) ! t._2.toArray
		}

		externalReduceJobs.clear ()
	}


	/**
	 * Iterates over local map jobs queue until it's empty
	 */
	private def processMapJobs(): Unit = {
		while (mapJobs.nonEmpty) {
			doMap (mapJobs.dequeue ())
		}


	}


	private def processReduceJobs(): Unit = {
		while (reduceJobs.nonEmpty) {
			doReduce (reduceJobs.dequeue ())
		}

	}


	/**
	 * Adds waiting map jobs to reduce jobs queue. Waiting map jobs must not be processed - we already know if this
	 * path is ok or not. If we don't - we wait :)
	 * @param key
	 * @return
	 */
	private def reduceAwaitingMapJobs(key: (Int, String), feasible: Boolean, leaf: Option[String]): Unit = {
		if (awaitingMapJobs.contains (key)) {
			awaitingMapJobs (key) foreach {
				job =>
					enqueueReduceJob (
						TraverseReduceJob (
							id = job.sender,
							sender = job.id,
							feasible = feasible,
							step = job.step - 1,
							leaf = leaf)
					)
			}
			awaitingMapJobs -= key
		}
	}


	// TODO select functions for vertex and edge are not taken into consideration yet

	/**
	 * Looks at vertex instance and creates map job for each edge
	 * @param vertex
	 * @param msg
	 * @param direction
	 * @return
	 */
	private def enqueueJobFromVertex(vertex: Vertex, msg: TraverseMapJob, direction: Set[EdgeDirection]): Int = {
		val toCheck = collection.mutable.TreeSet.empty[String]
		if (direction.contains (EdgeDirectionIn) || direction.isEmpty) toCheck ++= vertex.incoming
		if (direction.contains (EdgeDirectionOut) || direction.isEmpty) toCheck ++= vertex.outgoing
		if (direction.contains (EdgeDirectionBi) || direction.isEmpty) toCheck ++= vertex.bidirectional

		toCheck foreach {
			id =>
			// don't step back while map job - omit sender
				if (id != msg.sender) {
					val job = TraverseMapJob (id = id, msg.step + 1, sender = vertex.state.id)
					enqueueMapJob (job)
				}
		}

		toCheck.size
	}


	/**
	 * Looks at edge instance and creates map job for vertex
	 * @param edge
	 * @param previousVertex
	 * @param step
	 * @return
	 */
	private def enqueueJobFromEdge(edge: Edge, previousVertex: String, step: Int): Unit = {
		val es = edge.state
		val job = TraverseMapJob (
			id = if (es.fromVertex == previousVertex) es.toVertex else es.fromVertex,
			step + 1,
			sender = edge.state.id)

		enqueueMapJob (job)
	}


	private def enqueueMapJob(job: TraverseMapJob): Unit = {
		val destAddress = addressById (job.id)
		log.info ("Address for map job is {}", destAddress.toString)
		if (destAddress == cluster.selfAddress) {
			mapJobs += job
			log.info ("Map job enqueued locally({}): {}", mapJobs.size, job.toString)
		}
		else {
			if (!externalMapJobs.contains (destAddress))
				externalMapJobs (destAddress) = collection.mutable.Queue.empty[TraverseMapJob]
			externalMapJobs (destAddress) += job
			log.info ("Map job enqueued externally({}): {}", externalMapJobs.size, job.toString)
		}

	}


	private def enqueueReduceJob(job: TraverseReduceJob): Unit = {
		if (job.id != null) {
			val destAddress = addressById (job.id)
			if (destAddress == cluster.selfAddress) reduceJobs += job
			else {
				if (!externalReduceJobs.contains (destAddress))
					externalReduceJobs (destAddress) = collection.mutable.Queue.empty[TraverseReduceJob]
				externalReduceJobs (destAddress) += job
			}
			log.info ("Reduce job enqueued: {}", job.toString)
		}
	}


	/**
	 * Performs single map job, new map and reduce jobs may be produced.
	 * @param msg
	 */
	private def doMap(msg: TraverseMapJob): Unit = {
		log.info ("Map job %s step %s".format (msg.id, msg.step))

		require (memory.containsKey (msg.id))

		val element = memory.get (msg.id)

		// TODO check if currentQuery is defined at processed step

		// element may be selected or not based on filter function

		val graphElementSelected = element match {
			case v: Vertex =>
				currentQuery.filters (msg.step).asInstanceOf[VertexFilterFun](v)
			case e: Edge =>
				currentQuery.filters (msg.step).asInstanceOf[EdgeFilterFun](e)
		}

		graphElementSelected match {
			case true =>
				if (traversedByStepMap contains ((msg.step, msg.id))) mapVisited (msg)
				else mapNotVisited (element, msg)
			case false =>
				traversedByStepMap ((msg.step, msg.id)) = TraverseTreeItem (id = msg.id, step = msg.step, parent = msg.sender,
					status = TraverseTreeItem.Failed, numberOfActiveChildren = 0, numberOfFeasiblePaths = 0)

				enqueueReduceJob (
					TraverseReduceJob (id = msg.sender, sender = msg.id, feasible = false, step = msg.step - 1))
		}
	}


	private def mapNotVisited(element: GraphElement, msg: TraverseMapJob) {
		val te = element match {
			case v: Vertex =>
				// TODO directions from query at step
				if (currentQuery.filters.size > msg.step + 1) {
					val children = enqueueJobFromVertex (v, msg, Set.empty[EdgeDirection])
					TraverseTreeItem (id = msg.id, step = msg.step, parent = msg.sender,
						status = TraverseTreeItem.Active, numberOfActiveChildren = children, numberOfFeasiblePaths = 0)
				}
				else {
					enqueueReduceJob (
						TraverseReduceJob (
							id = msg.sender,
							sender = msg.id,
							feasible = true,
							step = msg.step - 1,
							leaf = Some (msg.id)))
					TraverseTreeItem (id = msg.id, step = msg.step, parent = msg.sender,
						status = TraverseTreeItem.Feasible, numberOfActiveChildren = 0, numberOfFeasiblePaths = 1)
				}

			case e: Edge =>
				if (currentQuery.filters.size > msg.step + 1) {
					enqueueJobFromEdge (e, msg.sender, msg.step)
					TraverseTreeItem (id = msg.id, step = msg.step, parent = msg.sender,
						status = TraverseTreeItem.Active, numberOfActiveChildren = 1, numberOfFeasiblePaths = 0)
				}
				else {
					enqueueReduceJob (
						TraverseReduceJob (
							id = msg.sender,
							sender = msg.id,
							feasible = true,
							step = msg.step - 1,
							leaf = Some (msg.id))
					)
					TraverseTreeItem (id = msg.id, step = msg.step, parent = msg.sender,
						status = TraverseTreeItem.Feasible, numberOfActiveChildren = 0, numberOfFeasiblePaths = 1)
				}
		}

		traversedByStepMap ((msg.step, msg.id)) = te
	}


	private def mapVisited(msg: TraverseMapJob) {
		// already visited in same step, if still active then we don't know if path is ok or not

		val currentTti = traversedByStepMap ((msg.step, msg.id))

		currentTti.status match {
			case TraverseTreeItem.Active =>
				awaitingMapJobs ((currentTti.step, currentTti.id)) += msg

			case TraverseTreeItem.Failed =>
				// reduce
				enqueueReduceJob (
					TraverseReduceJob (
						id = msg.sender,
						sender = msg.id,
						feasible = false,
						step = msg.step - 1,
						leaf = None))

			case TraverseTreeItem.Feasible =>
				// reduce
				enqueueReduceJob (
					TraverseReduceJob (
						id = msg.sender,
						sender = msg.id,
						feasible = true,
						step = msg.step - 1,
						leaf = None))
		}
	}


	/**
	 * Performs single reduce job, new reduce jobs may be produced.
	 * @param msg
	 */
	private def doReduce(msg: TraverseReduceJob): Unit = {
		require (traversedByStepMap contains ((msg.step, msg.id)))

		var currentTti = traversedByStepMap ((msg.step, msg.id))
		val activeChildren = currentTti.numberOfActiveChildren - 1
		val reduceMessage = TraverseReduceJob (
			id = currentTti.parent, feasible = msg.feasible, sender = currentTti.id, step = currentTti.step - 1, leaf = msg.leaf)

		if (currentTti.parent == null && msg.leaf.isDefined)
			feasibleElements += msg.leaf.get

		currentTti = currentTti.status match {
			case TraverseTreeItem.Active =>

				msg.feasible match {
					case true =>
						// Enqueue awaiting map jobs and change status to Feasible. Continue reduce.

						reduceAwaitingMapJobs ((currentTti.step, currentTti.id), msg.feasible, msg.leaf)
						enqueueReduceJob (reduceMessage)
						currentTti.copy (
							numberOfActiveChildren = activeChildren,
							numberOfFeasiblePaths = currentTti.numberOfFeasiblePaths + 1,
							status = TraverseTreeItem.Feasible
						)


					case false =>
						if (activeChildren > 0) {
							// Active children exists, no status change as there still may be feasible paths in children.
							// Waiting map jobs are not enqueued. Reduce is stopped.
							currentTti.copy (
								numberOfActiveChildren = activeChildren,
								status = TraverseTreeItem.Active
							)
						} else {
							// No active children, change status to failed and process awaiting map jobs.
							// Reduce is continued.
							reduceAwaitingMapJobs ((currentTti.step, currentTti.id), msg.feasible, msg.leaf)
							enqueueReduceJob (reduceMessage)
							currentTti.copy (
								numberOfActiveChildren = activeChildren,
								status = TraverseTreeItem.Failed
							)
						}
				}

			case TraverseTreeItem.Failed =>
				throw new IllegalStateException ("Reduce job detected on TraverseTreeItem in state Failed")

			case TraverseTreeItem.Feasible =>
				// Reduce is discontinued - already took place when Active to Feasible transition has been performed
				currentTti.copy (numberOfActiveChildren = activeChildren)
		}

		traversedByStepMap ((msg.step, msg.id)) = currentTti

		if (currentTti.numberOfActiveChildren == 0 && currentTti.parent == null) {
			traversingFinished = true
		}
	}


}


