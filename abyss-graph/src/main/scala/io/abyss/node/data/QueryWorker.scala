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

import java.util.concurrent.ConcurrentMap

import akka.cluster.Member
import io.abyss.graph.model.GraphElement


/*
 * Created by cane, 12.07.13 20:20
 * $Id: QueryWorker.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */


/**
 * Worker for simple queries, started by query manager
 * @param memory Node memory doMap
 */
class QueryWorker(val memory: ConcurrentMap[ String, GraphElement ]) extends QueryProcessor {

	var nodeShardMap: Map[Member, Set[Short]] = null

	def receive  = receiveQuery
}
