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

package io.abyss.node

import akka.actor.Address
import akka.cluster.{MemberStatus, Member}


/**
 * Created by cane, 28.06.13 21:58
 * $Id: ClusterMembersCollector.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
trait ClusterMembersCollector  {


	/**
	 * Minimum cluster size before nodes goes to started state
	 * TODO Should be taken from Akka configuration file
	 */
	val MinClusterSize = 2


	var leader: Option[ Address ] = None

	var roleLeaders = Map.empty[ String, Option[ Address ] ]


	/**
	 * All cluster members sorted by age
	 */
	var membersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)

	/**
	 * Front role members sorted by age
	 */
	var frontMembersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)

	/**
	 * Back role members sorted by age
	 */
	var backMembersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)


	/**
	 * Member has been moved to Up state by cluster leader
	 * @param member
	 */
	protected[node] def memberUp(member: Member) = {
		membersByAge += member

		if ( member.hasRole(FrontRoleName) ) {
			frontMembersByAge += member
		}

		if(membersByAge.size == MinClusterSize) {
			// TODO start node? what about convergence here? nodes works in separation!
		}
	}


	/**
	 * Member has been moved to Removed state by cluster leader
	 * @param member
	 * @param memberStatus
	 */
	protected[node] def memberRemoved(member: Member, memberStatus: MemberStatus) = {
		membersByAge -= member

		if ( member.hasRole(FrontRoleName) ) {
			frontMembersByAge -= member
		}

		if ( membersByAge.size < MinClusterSize ) {
			// TODO cluster degradation detected
		}
	}


	protected[node] def leaderChanged(newLeader: Option[Address]) = {
		leader = newLeader
	}


	protected[node] def roleLeaderChanged(role: String, newLeader: Option[Address]) = {
		newLeader match {
			case Some(a) => roleLeaders += (role -> newLeader)
			case None => roleLeaders -= role
		}

	}


	/**
	 * Builds singleton address from path. Singleton is always started on oldest node.
	 * TODO
	 * @param path
	 */
	def singletonAddress(path: String) = {

	}
}
