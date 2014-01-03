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
import akka.actor.ActorRef

// Created by cane, 1/2/14 1:08 PM

package object client {
	case class ClientConnected (clientRef: ActorRef)


    // TODO client could get reference to front member directly from one of selected by cluster
	case class AbyssFrontMembers (members: Set[ Member ])





	// TODO commands should be propagated to persistence layer, where they may have impact on DB structure

	trait Command {
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



	// GRAPH queries - with some types defined for filtering ----------------------------------------------------



	case class InVertices (id: String, graph: String, filter: VertexFilterFun) extends Query



	case class OutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query


	case class InOutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query


	case class InEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


	case class OutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


	case class InOutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query







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
	 * Holds array of ids found via given query
	 * TODO returning whole data object
	 * @param ids
	 */
	case class TraversingResult(ids: Array[String])





    // Types for filter functions

    type VertexFilterFun = ( VertexState ) => Boolean

    type EdgeFilterFun = ( EdgeState ) => Boolean


    /**
     * Filter function for starting node.
     */
    val V: Array[ Any ] = Array[ Any ](
    {
        _: VertexState => true // accept start vertex
    })


}
