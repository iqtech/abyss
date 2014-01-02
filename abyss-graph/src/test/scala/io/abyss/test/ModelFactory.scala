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

package io.abyss.test


/**
 * Created by cane, 12/2/13 5:41 PM
 * $Id: ModelFactory.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class ModelFactory {

//	def produce(map: Map[String, Any], collection: String, pkg: String): Any = {
//		val clazz = getClass.getClassLoader.loadClass(pkg+"."+collection)
//		//val clazz = getClass.getClassLoader.loadClass("pl.iqtech.abyss.graph.test$Galaxy")
//		val obj = clazz.newInstance()
//		clazz.getDeclaredFields.foreach {
//			fld =>
//				fld.setAccessible(true)
//				if(map.contains(fld.getName)) fld.set(obj, map(fld.getName))
//		}
//		obj
//	}
//
//
//	@Test
//	def factoryTest = {
//		val map = Map(
//			"name" -> "Andromeda",
//			"mass" -> 1.0
//		)
//		val g = produce(map = map, collection = "Galaxy", pkg = "pl.iqtech.abyss.graph.test").asInstanceOf[Galaxy]
//		println(g)
//	}
}
