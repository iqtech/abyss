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

package io.abyss.graph.model

import java.util.UUID
import scala.Some
import io.abyss.client.{GraphElementState, EdgeState}


/*
 * Created by cane, 11.06.13 17:27
 * $Id: Edge.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */

// TODO make context for select functions


/**
 * Graph representation of edge. Has state which may be changed during on-graph operations.
 * @param initialState Initial edge state, may be None when recovering from disk and no data were load
 * @param initialDirty Initial dirty value
 */
final class Edge (initialState: Option[ EdgeState ], initialDirty: Boolean = true)
	extends GraphElement {

	/**
	 * Current edge state
	 */
	protected var internalState: Option[ GraphElementState ] = initialState

	/**
	 * Marker of dirty state (not saved)
	 */
	protected var internalDirty = initialDirty


	/**
	 * State accessor
	 * @return
	 */
	override def state: EdgeState = internalState match {
		case Some (s) =>
			s.asInstanceOf[ EdgeState ]
		case None =>
			throw new IllegalStateException ()
	}


	/**
	 * Returns this object if selectFun returns true, else nothing
	 * @param selectFun Edge selector function, will be executed in graph context.
	 * @return Some(this) or None, according to selectFun result
	 */
	def select (selectFun: ( Edge ) => Boolean): Option[ this.type ] = {
		if ( selectFun (this) ) return Some (this)
		else return None
	}


}


object Edge {

	def apply (id: String = UUID.randomUUID ().toString, graph: String,
			   fromVertex: String, toVertex: String, bidirectional: Boolean, data: Option[AnyRef]): Edge = {
		val es = EdgeState (id, graph, fromVertex, toVertex, bidirectional, data)
		new Edge (Some (es))
	}


	def apply (map: Map[ String, Any ]): Edge = {
		Edge (
			id = map ("id").asInstanceOf[ String ],
			graph = map ("graph").asInstanceOf[ String ],
			fromVertex = map ("fromVertex").asInstanceOf[ String ],
			toVertex = map ("toVertex").asInstanceOf[ String ],
			bidirectional = map ("bidirectional").asInstanceOf[ Boolean ],
			data = None
		)
	}
}
