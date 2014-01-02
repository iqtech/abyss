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

import akka.actor.ActorRef
import java.util.concurrent.ConcurrentMap
import scala.collection.JavaConverters
import io.abyss.AbyssActor
import io.abyss.graph.model.GraphElement


/*
 * Created by cane, 02.07.13 18:02
 * $Id: ShardTransferWorker.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */




class ShardTransferProducer(val memory: ConcurrentMap[ String, GraphElement ], val ref: ActorRef) extends AbyssActor {

	val SendBufferSize = 1000

	val toSend = JavaConverters.asScalaSetConverter(memory.keySet).asScala

	val buffer = collection.mutable.HashMap.empty[ String, GraphElement ]

	def receive = {

		case msg: ShardTransferRequested =>
			var i = 0
			toSend foreach {
				key =>
					buffer += ( key -> memory.get(key) )
					i += 1
					if ( i % SendBufferSize == 0 ) {
						// TODO remote call
						ref ! ShardTransferData(buffer.toMap)
						buffer.clear()
					}
			}

			if ( !buffer.isEmpty ) {
				// TODO remote call
				ref ! ShardTransferData(buffer.toMap)
				buffer.clear()
			}

			// TODO remote call
			ref ! ShardTransferCompleted
			context.parent ! ShardTransferCompleted
	}
}



class ShardTransferConsumer(val memory: ConcurrentMap[ String, GraphElement ]) extends AbyssActor {

	def receive = {
		case msg: ShardTransferData =>
			memory.putAll(JavaConverters.mapAsJavaMapConverter(msg.map).asJava)

		case ShardTransferCompleted =>
			context.parent ! ShardTransferCompleted

		case msg =>
	}

}
