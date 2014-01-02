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
import akka.cluster.{Cluster, Member}
import io.abyss.graph.model.{Vertex, Edge}


/**
 * Created by cane, 09.07.13 21:41
 * $Id: package.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
package object abyss {


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


	case class ClientConnected ()


	case class AbyssFrontMembers (members: Set[ Member ])


	type VertexFilterFun = ( Vertex ) => Boolean

	type EdgeFilterFun = ( Edge ) => Boolean


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



	// GRAPH queries - with some types defined for filtering ----------------------------------------------------



	case class InVertices (id: String, graph: String, filter: VertexFilterFun) extends Query



	case class OutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query


	case class InOutVertices (id: String, graph: String, filter: VertexFilterFun) extends Query


	case class InEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


	case class OutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


	case class InOutEdges (id: String, graph: String, filter: EdgeFilterFun) extends Query


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


	val V = Array[ Any ](
		{
			_: Vertex => true // accept start vertex
		})


}
