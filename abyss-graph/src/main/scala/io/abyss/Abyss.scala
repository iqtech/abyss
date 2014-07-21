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

import akka.actor._
import io.abyss.node.{ClusterManager, NodeManager}


/**
 * Main object of Abyss Graph, starts actor system and managers.
 * Created by cane, 11.06.13 12:54
 * $Id: Abyss.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */

object Abyss {

	val system = ActorSystem ("abyss")

	val nodeManager = system.actorOf (Props[ NodeManager ], "node")
	val clusterManager = system.actorOf (Props[ ClusterManager ], "cluster")


}








