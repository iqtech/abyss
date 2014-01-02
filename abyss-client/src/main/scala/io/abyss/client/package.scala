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

package io.abyss

import akka.cluster.Member

// Created by cane, 1/2/14 1:08 PM

package object client {
	case class ClientConnected ()


	case class AbyssFrontMembers (members: Set[ Member ])





	// TODO commands should be propagated to persistence layer, where they may have impact on DB structure

	sealed trait Command {
		val id: String
	}


	case class CommandSeq(commands: Seq[Command])

	trait Query


	trait QueryMany extends Query {
		val ids: Seq[ String ]
	}


	case class QueryTraversable(id: String, startAt: String, filters: Array[Any]) extends Query {

	}


	// CRUD case classes -----------------------------------------------------------------------------------------

	case class CreateGraph (id: String) extends Command


	case class DeleteGraph (id: String) extends Command


	case class CreateVertex (id: String, graph: String, data: Option[AnyRef]) extends Command


	case class CreateEdge (id: String, graph: String, from: String, to: String, bi: Boolean, data: Option[AnyRef]) extends Command


	case class Read (id: String, graph: String) extends Query


	case class ReadMany (ids: Seq[ String ], graph: String) extends QueryMany


	case class Update (id: String, graph: String, data: AnyRef) extends Command


	case class Delete (id: String, graph: String) extends Command



//	// GRAPH queries - with some types defined for filtering ----------------------------------------------------
//
//
//
//	case class InVertices (id: String, graph: String, filter: VertexFilterFun) extends Query
//
//
//
//	case class OutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query
//
//
//	case class InOutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query
//
//
//	case class InEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query
//
//
//	case class OutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query
//
//
//	case class InOutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


	// Traversable queries
	// V("user") --> {
	//   es=>es.data.asInstanceOf[MyEdgeData].myBoolean == true
	// } --> {
	//   vs=> vs.data.asInstanceOf[MyVertexData].myInt > 0
	// }



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


	// In-graph events -----------------------------------------------------------------------------------------


	/**
	 * Returned when no element can be found
	 */
	case object NoSuchElement


	/**
	 * Returned when element exists in memory
	 */
	case object ElementExists


	/**
	 * Trait which defines command results like Processed or Failed
	 */
	trait CommandProcessingResult

	/**
	 * Returned to client when command successfully processed
	 */
	case object CommandProcessed extends CommandProcessingResult


	/**
	 * Returned when something wrong happened during command processing
	 * @param msg Error message
	 */
	case class CommandFailed (msg: String) extends CommandProcessingResult


	/**
	 * Sent when edge has been created and remote shard must update indices held by vertex.
	 * @param id
	 */
	case class VertexInternalIndexUpdateRequired (id: String, from: String, to: String, bi: Boolean) extends Command


	/**
	 * Holds array of ids found via given query
	 * TODO returning whole data object
	 * @param ids
	 */
	case class TraversingResult(ids: Array[String])



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


}
