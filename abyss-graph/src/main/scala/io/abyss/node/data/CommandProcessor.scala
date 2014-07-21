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

import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.ConcurrentMap
import scala.concurrent.duration._
import io.abyss.graph.model.{GraphElement, Edge, Vertex}
import io.abyss._
import io.abyss.client._
import scala.concurrent.ExecutionContext

/*
 * Created by cane, 13.07.13 12:27
 * $Id: CommandProcessor.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */


/**
 * Added to shard workers, provides command processing functionality.
 */
trait CommandProcessor extends AbyssActor {
	/**
	 * Shard ID of this processor
	 */
	val sid: Short

	/**
	 * Node shards memory pointer
	 */
	val memory: ConcurrentMap[String, GraphElement]


	/**
	 * Default timeout for async operations
	 */
	implicit val defaultTimeout = Timeout (2 seconds)


	def idsBelongsToMe(ids: Array[String]) = (true /: ids) {
		(res, id) => res || (shardId (id) == sid)
	}


	// TODO implement persistence via message sending

	protected[data] def processCommand(command: Command) = command match {

		case cmd: CreateVertex if shardId (cmd.id) == sid =>
			val askedBy = sender
			val v = Vertex (cmd.id, cmd.graph, cmd.data)
			val old = memory.putIfAbsent (cmd.id, v)

			if (old != null) {
				log.debug ("Vertex already exists: {}", cmd)
				askedBy ! CommandFailed ("Vertex exists")

			} else {
				log.debug ("Vertex stored: {}", cmd)
				sender ! CommandProcessed
			}


		case cmd: CreateEdge if shardId (cmd.id) == sid =>
			val askedBy = sender

			if (memory.containsKey (cmd.id)) {
				askedBy ! CommandFailed ("Edge exists")

			} else {
                import ExecutionContext.Implicits.global
				// Index update in connected vertices, VertexInternalIndexUpdateRequired is sent to selected shard worker.
				// Integrity is required - check vertices existence.

				// TODO worst case scenario implemented, when both vertices must be checked externally (use my shards when possible)

				val fromSel = context.actorSelection ("../%s" format shardId (cmd.from))
				val toSel = context.actorSelection ("../%s" format shardId (cmd.to))

				val fromFuture = ask (fromSel, VertexIntegrityRequired (cmd.from))
				val toFuture = ask (toSel, VertexIntegrityRequired (cmd.to))

				val future = for {
					fromExistence <- fromFuture.mapTo[Any]
					toExistence <- toFuture.mapTo[Any]
				} yield (fromExistence, toExistence)

				log.debug ("Asking for vertices existence")

				future foreach {
					case (ElementExists, ElementExists) =>

						val e = Edge (cmd.id, cmd.graph, cmd.from, cmd.to, cmd.bi, cmd.data)
						memory.put (cmd.id, e)

						fromSel ! VertexInternalIndexUpdateRequired (cmd.id, cmd.from, cmd.to, cmd.bi)
						toSel ! VertexInternalIndexUpdateRequired (cmd.id, cmd.from, cmd.to, cmd.bi)
						askedBy ! CommandProcessed
						log.debug ("Stored: {}", cmd)

					case (NoSuchElement, NoSuchElement) | (NoSuchElement, ElementExists) | (ElementExists, NoSuchElement) =>
						askedBy ! CommandFailed ("Some vertices can not be found")
						log.debug ("Some vertices can not be found, error sent to: {}", askedBy.path)
				}
			}


		case cmd: VertexInternalIndexUpdateRequired if shardId (cmd.from) == sid || shardId (cmd.to) == sid =>
			log.debug ("Updating vertex's edge indices")
			updateVertices (cmd.id, cmd.from, cmd.to, cmd.bi)

		case cmd: VertexIntegrityRequired =>
			val v = memory.get (cmd.id)
			sender ! (if (v != null && v.isInstanceOf[Vertex]) ElementExists else NoSuchElement)

		case cmd: EdgeIntegrityRequired =>
			val e = memory.get (cmd.id)
			sender ! (if (e != null && e.isInstanceOf[Edge]) ElementExists else NoSuchElement)

		case cmd =>
			// TODO send error
			log.warning ("Uncaught command: {}", cmd.toString)
	}


	/**
	 *
	 * @param id
	 * @param from
	 * @param to
	 * @param bi
	 * @return Tuple of (true, true) if both vertices (from, to) were updated locally, false in correct place otherwise.
	 */
	private def updateVertices(id: String, from: String, to: String, bi: Boolean): (Boolean, Boolean) = {
		var fromLocal, toLocal: Boolean = false

		if (shardId (from) == sid) {
			val v = memory.get (from).asInstanceOf[Vertex]
			if (v == null) {
				// TODO vertex 'from' doesn't exist, error
			}
			else {
				if (bi) v.addBiEdge (id) else v.addOutEdge (id)
				fromLocal = true
			}
		}


		if (shardId (to) == sid) {
			val v = memory.get (to).asInstanceOf[Vertex]
			if (v == null) {
				// TODO vertex 'to' doesn't exist, error
			}
			else {
				if (bi) v.addBiEdge (id) else v.addInEdge (id)
				toLocal = true
			}
		}

		(fromLocal, toLocal)
	}
}
