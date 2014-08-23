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

package io.abyss.graph.model

import java.util.UUID

import io.abyss._
import io.abyss.client._


/*
 * Created by cane, 11.06.13 17:28
 */

// TODO make context for select functions, which will describe some aspects like security or access control

/**
 * Vertex represents logic around VertexState. Not thread safe.
 * @param initialState
 * @param initialDirty
 */
final class Vertex (initialState: Option[VertexState], initialDirty: Boolean = true) extends GraphElement {


	// internal indices

	private val inEdges = collection.mutable.TreeSet.empty[ String ]

	private val outEdges = collection.mutable.TreeSet.empty[ String ]

	private val biEdges = collection.mutable.TreeSet.empty[ String ]



	protected var internalState: Option[GraphElementState] = initialState

	protected var internalDirty: Boolean = initialDirty



	override def state: VertexState = internalState match {
		case Some(s) =>
			s.asInstanceOf[ VertexState ]
		case None =>
			throw new IllegalStateException()
	}




	//	/**
	//	 * State setter, also invokes touch to set dirty flag
	//	 * @param s
	//	 */
	//	def state_= (s: VertexState): Unit = {
	//		state = s
	//		touch()
	//	}


	def addInEdge (id: String): Unit = inEdges += id


	def addOutEdge (id: String): Unit = outEdges += id


	def addBiEdge (id: String): Unit = biEdges += id


	def incoming = inEdges.toArray


	def outgoing = outEdges.toArray


	def bidirectional = biEdges.toArray


	/**
	 * Returns this instance when call to selectFun returns true, nothing elsewhere.
	 * @param selectFun Function to be called on vertex's state
	 * @return Some(this) or None according to selctFun result
	 */
	def select (selectFun: VertexFilterFun): Option[ this.type ] = {
		if ( selectFun (state) ) Some (this)
		else None
	}


}


object Vertex {

	def apply (id: String = UUID.randomUUID ().toString, graph: String, data: Option[AnyRef]): Vertex = {
		val vs = VertexState (id, shardId(id), graph, data)
		new Vertex (Some(vs))
	}


	def apply (map: Map[ String, Any ]): Vertex = {
		Vertex (
			id = map ("id").asInstanceOf[ String ],
			graph = map ("graph").asInstanceOf[ String ],
			data = None
		)
	}

}
