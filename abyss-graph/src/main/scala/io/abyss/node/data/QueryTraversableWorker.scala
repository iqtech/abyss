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

import akka.actor.{ActorRef, Address}
import java.util.concurrent.ConcurrentMap
import io.abyss.client._
import io.abyss.node.{CoordinatedWorkDone, StartCoordinatedWork, WorkerReady}
import io.abyss.graph.model.GraphElement


/**
 * Created by cane, 26.07.13 13:07
 * $Id: QueryTraversableWorker.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */
class QueryTraversableWorker (val memory: ConcurrentMap[ String, GraphElement ],
							  val shardMap: Array[ Address ])
	extends QueryTraversableProcessor {

	var coordinator: ActorRef = _

	def receive = {

		case msg: QueryTraversable =>
			currentQuery = msg
			sender ! WorkerReady (self)


		case msg: StartCoordinatedWork =>
			coordinator = msg.coordinator
			startTraversingAt (currentQuery.startAt)


		case msg: Array[ TraverseMapJob ] =>
			mapJobsReceived (msg)
			if(finished) coordinator ! CoordinatedWorkDone(feasibleElements.toArray)

		case msg: Array[ TraverseReduceJob ] =>
			reduceJobsReceived (msg)
			if(finished) coordinator ! CoordinatedWorkDone(feasibleElements.toArray)

		case msg =>
			log.warning ("Unknown message: {}", msg.toString)
	}
}


