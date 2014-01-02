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

package io.abyss.storage

import akka.actor.{Props, ActorRef}
import akka.camel.Consumer
import io.abyss.AbyssActor
import io.abyss.graph.internal.{EdgeState, VertexState}


/**
 * Created by cane, 12.06.13 14:52
 * $Id: StorageManager.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class StorageManager extends AbyssActor {

	val dirtyVertices = scala.collection.mutable.HashSet.empty[ ActorRef ]
	val dirtyEdges = scala.collection.mutable.HashSet.empty[ ActorRef ]

	context.actorOf (Props[ StoragePersistenceTicker ], "clock")


	def receive = {
//		case msg: IAmDirtyVertex =>
//			dirtyVertices += msg.ref
//
//		case msg: IAmDirtyEdge =>
//			dirtyEdges += msg.ref
//
//		case msg: PersistRequired =>
//			for ( d <- dirtyVertices ) d ! msg
//			for ( e <- dirtyEdges ) e ! msg
//			dirtyVertices.clear
//			dirtyEdges.clear
//
//		case msg: VertexState =>
//			// TODO
//			log warning "No persister for %s".format (msg.toString)
//
//		case msg: EdgeState =>
//			// TODO
//			log warning "No persister for %s".format (msg.toString)
//
//
		case msg => log debug msg.toString
	}

}


/**
 * Each time started sends PersistReuired() message to storage cluster
 */
class StoragePersistenceTicker extends AbyssActor with Consumer {


	def endpointUri = "quartz://abyss/storage?cron=0/5+*+*+*+*+?"


	def receive = {
		case msg =>
			//storageManager ! PersistRequired ()
	}
}



class StorageAbstraction[ A ] {

	def contains (id: String) = false


	def apply (id: String): Option[ A ] = None
}


object StorageManager {
	// TODO remove maps and cover underlying physical storage, currently nothing (in-memory)
	val vertices = new StorageAbstraction[ VertexState ]()
	val edges = new StorageAbstraction[ EdgeState ]()

}


//			val futures = for ( d <- dirties ) yield ( ask (d, msg).mapTo[ VertexState ] )
//			futures.foreach {
//				f =>
//					f.foreach {
//						state =>
//							log.debug ("ShardState - do something: %s" format state.toString)
//					}
//			}

