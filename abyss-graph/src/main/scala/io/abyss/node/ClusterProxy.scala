package pl.iqtech.abyss.node

import io.abyss.AbyssActor


/**
 * Knows cluster so may forward messages to other cluster nodes.
 * Created by cane, 28.06.13 21:22
 * $Id: ClusterProxy.scala,v 1.2 2014-01-02 09:35:15 cane Exp $
 */
class ClusterProxy extends AbyssActor {

	def receive = {
		case msg =>
	}
}
