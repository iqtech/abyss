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

import akka.cluster.VectorClock
import io.abyss.client.GraphElementState


/*
 * Created by cane, 11.06.13 15:31
 * $Id: GraphElement.scala,v 1.1 2014-01-02 09:35:14 cane Exp $
 */


/**
 * Marker used by Node.memory
 */
trait AbstractGraphElement

/**
 * Represents vertex or edge which is not loaded into memory. Stored in memory under it's key
 * (so we don't have to store id inside marker)
 * @param vOrE
 */
case class UnloadedGraphElement(vOrE: Char) extends AbstractGraphElement


/**
 * Element loaded into memory base
 */
trait GraphElement extends AbstractGraphElement {

	/**
	 * True when element must be saved
	 */
	protected var internalDirty: Boolean
	protected var internalState: Option[GraphElementState]

	protected var internalVersion = new VectorClock ()

	/**
	 * Version of element
	 * @return Current element version
	 */
	def version = internalVersion

	/**
	 * State of element
	 * @return Current element state
	 */
	def state: GraphElementState




	/**
	 * Updates value of state held by this instance
	 * @param newState New state
	 */
	def state_=(newState: Option[GraphElementState]) {
		internalState = newState
	}


	/**
	 * Access to dirty variable
	 * @return True if element has been modified and not persisted
	 */
	def dirty = internalDirty

	/**
	 * Call to mark element as dirty, will be persisted in the future. Sends IAmDirtyVertex message to storage to
	 * be a part of next persistence process. This message is sent only if previously wasn't dirty.
	 */
	def touch() {
		internalDirty = true
		internalVersion = new VectorClock ()
	}




	/**
	 * Cleans dirty flag
	 */
	def clean() {
		internalDirty = false
	}

}


//class Index (val id: String,
//			 val shard: scala.collection.mutable.HashMap[ String, Any ])
//	extends GraphElement {
//
//	def receive = {
//		case msg => log info msg.toString
//	}
//}
