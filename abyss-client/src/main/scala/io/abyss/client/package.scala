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

package io.abyss

import akka.actor.ActorRef
import akka.cluster.Member

// Created by cane, 1/2/14 1:08 PM

package object client {

	/**
	 * Sent by client to server, expects AbyssFrontMembers in return
	 * @param clientRef Client actor reference
	 */
	case class ClientSpawned( clientRef: ActorRef )


	// TODO client could get reference to front member directly from one of selected by cluster

	case class AbyssFrontMembers( members: Set[Member] )


	/**
	 * Represents Abyss Primary Key, form is shard:suid, fe. 123:778212331
	 * @param shard shard number
	 * @param suid shard unique id
	 */
	case class AbyssPK( shard: Short, suid: Long ) {
		def unapply: String = s"$shard:$suid"
		override def toString = unapply
	}

	object AbyssPK {

		/**
		 * Translate string shard:suid to AbyssPK instance
		 * @param s
		 * @return Some(pk) or None if parse error
		 */
		def apply( s: String ): Option[AbyssPK] = {
			try {
				var shard: Short = -1
				val builder = new StringBuilder
				0 to s.length - 1 foreach {
					sp =>
						val c = s.charAt( sp )
						if( c == ':' ) {
							shard = builder.toString( ).toShort
							builder.clear( )
						}
						else {
							builder.append( c )
						}
				}
				val idx = builder.toString.toLong

				if( shard >= 0 && idx >= 0 ) Some( AbyssPK( shard = shard, suid = idx ) )
				else None
			} catch {
				case e: NumberFormatException =>
					None
			}
		}
	}


	trait AbyssCreateCommand {
		val data: Any
	}

	trait AbyssUpdateCommand {

		/**
		 * Identifier of data in graph, formed by shardNo:inShardIdx, fe/ 1:1 means shard 1, object 1
		 *
		 */
		val id: String
	}


	// TODO commands should be propagated to persistence layer, where they may have impact on DB structure

	trait Command {
		val id: String
	}


	case class CommandSeq( commands: Seq[Command] )

	trait Query


	trait QueryMany extends Query {
		val ids: Seq[String]
	}


	case class QueryTraversable( id: String, startAt: String, filters: Array[Any] ) extends Query {

	}


	// CRUD case classes -----------------------------------------------------------------------------------------

	case class CreateGraph( id: String ) extends Command


	case class DeleteGraph( id: String ) extends Command


	case class CreateVertex( id: String, graph: String, data: Option[AnyRef] ) extends Command


	case class CreateEdge( id: String, graph: String, from: String, to: String, bi: Boolean, data: Option[AnyRef] ) extends Command


	case class Read( id: String, graph: String ) extends Query


	case class ReadMany( ids: Seq[String], graph: String ) extends QueryMany


	case class Update( id: String, graph: String, data: AnyRef ) extends Command


	case class Delete( id: String, graph: String ) extends Command


	// GRAPH queries - with some types defined for filtering ----------------------------------------------------


	case class InVertices( id: String, graph: String, filter: VertexFilterFun ) extends Query


	case class OutVertices( id: String, graph: String, filter: VertexFilterFun ) extends Query


	case class InOutVertices( id: String, graph: String, filter: VertexFilterFun ) extends Query


	case class InEdges( id: String, graph: String, filter: EdgeFilterFun ) extends Query


	case class OutEdges( id: String, graph: String, filter: EdgeFilterFun ) extends Query


	case class InOutEdges( id: String, graph: String, filter: EdgeFilterFun ) extends Query


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
	case class CommandFailed( msg: String ) extends CommandProcessingResult


	/**
	 * Holds array of ids found via given query
	 * TODO returning whole data object
	 * @param ids
	 */
	case class TraversingResult( ids: Array[String] )


	// Types for functions - filtering, unmarshalling

	type VertexFilterFun = ( VertexState ) => Boolean

	type EdgeFilterFun = ( EdgeState ) => Boolean

	type DataUnmarshalFun = ( Map[String, Any] ) => Any


	case class DataUnmarshallingConfiguration( callbacks: Map[Any, DataUnmarshalFun] = Map.empty )

	/**
	 * Returns map as map
	 */
	val UnmarshalMap = {
		m: Map[String, Any] => m
	}

	val AllToMapUnmarshalConfiguration = DataUnmarshallingConfiguration(
		Map(
			AnyRef -> UnmarshalMap
		)
	)


	/**
	 * Filter function for starting node.
	 */
	val V: Array[Any] = Array[Any](
	{
		_: VertexState => true // accept start vertex
	} )


}
