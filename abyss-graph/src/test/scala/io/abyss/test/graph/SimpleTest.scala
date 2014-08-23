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

package io.abyss.test.graph

import java.util.UUID

import io.abyss._
import org.junit.Test

/*
 * Created by cane, 08.07.13 12:55
 * $Id: SimpleTest.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */

case class CC(id: String, name: String, descr: String)


@Test
class SimpleTest {

	@Test
	def testGeneric = {
		println(this.getClass.getSimpleName)
	}



	@Test
	def testCcToMap {
		val planet = Planet("Earth", 2E4)

		val cc = CC(UUID.randomUUID().toString, "AAaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

		val ccp = cc.copy()

		println(cc.toString)

		def qq(x: Int) {
			x + 1
		}

		val qqv = qq(1)

		val map = ccToMap(planet)

		println (map)
	}






	//	@Test
	//	def testReturnVal {
	//		def testFun {
	//			val x = 10
	//			x
	//		}
	//
	//		val x=testFun
	//		println(x.getClass.getName)
	//	}

}
