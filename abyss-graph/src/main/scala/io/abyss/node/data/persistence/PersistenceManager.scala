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

package io.abyss.node.data.persistence

import akka.actor.ActorSystem
import io.abyss.AbyssActor
import io.abyss.node.AbyssClusterState
import io.abyss.node.data._
import io.abyss.node.persistence.{AbyssDefaultPersistenceConfig, CollectionConsistencyConfig}

/**
 * Created by cane, 8/16/13 2:03 PM
 * $Id: PersistenceManager.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class PersistenceManager extends AbyssActor {


	val apc = AbyssDefaultPersistenceConfig()
	val types = PersistenceManager.readConsistencyConf(context.system)

	//val provider = context.actorOf(Props(new CassandraPersistenceProvider(apc, types)), apc.global.provider)

	var abyssClusterState: Option[AbyssClusterState] = None


	def receive = {
		case acs: AbyssClusterState =>
			abyssClusterState = Some(acs)

		case msg =>
			log.warning("Uncaught message: {}", msg.toString)
	}


}


object PersistenceManager {
    private def readConsistencyConf(actorSystem: ActorSystem): Array[CollectionConsistencyConfig] = {

        val conf = actorSystem.settings.config
        var res = Array.empty [CollectionConsistencyConfig]

        val collectionsConf = if(conf.hasPath(CollectionsConsistencyConfigKey))
            conf.getConfigList(CollectionsConsistencyConfigKey)
        else
            return res

        0 to collectionsConf.size() - 1 foreach {
            i =>
                val colConf = collectionsConf.get(i)
                val collection = colConf.getString("name")

                // TODO cr and cw should be declared as strings, then mapped to enum
                //val cr = if(colConf.hasPath("cr")) colConf.getInt("cr") else 1
                //val cw = if(colConf.hasPath("cw")) colConf.getInt("cw") else 1

                val tpp = CollectionConsistencyConfig(collection)
                res = res :+ tpp
        }
        res
    }

}
