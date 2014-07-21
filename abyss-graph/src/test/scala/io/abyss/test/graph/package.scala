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

package io.abyss.test

import java.util.{UUID, Date}
import io.abyss.client._

/**
 * Created by cane, 02.08.13 12:37
 * $Id: package.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
package object graph {


    trait CelestialBody {
        val name: String
        val mass: BigDecimal
    }


    case class Galaxy(name: String, mass: BigDecimal) extends CelestialBody


    case class Star(name: String, mass: BigDecimal) extends CelestialBody


    case class Planet(name: String, mass: BigDecimal) extends CelestialBody


    case class Moon(name: String, mass: BigDecimal) extends CelestialBody


    case class Satellite(name: String, launched: Date)


    case class Region(name: String)


    case class Orbits(averageDistance: Double, maxDistance: Double, minDistance: Double)


    case class MemberOf()


    val solarSystem = Array(

        // stars
        CreateVertex(id = "sun", graph = "solar", Some(Star("Sun", 1234567890.01))),
        CreateVertex(id = "alphaCentauri", graph = "Alpha Centauri", Some(Star("Alpha Centauri", 1234567890.01))),
        // planets
        CreateVertex(id = "mercury", graph = "solar", Some(("Mercury", 1234567890.01))),
        CreateVertex(id = "venus", graph = "solar", Some(Planet("Venus", 1234567890.01))),
        CreateVertex(id = "earth", graph = "solar", Some(Planet("Earth", 1234567890.01))),
        CreateVertex(id = "mars", graph = "solar", Some(Planet("Mars", 1234567890.01))),
        CreateVertex(id = "jupiter", graph = "solar", Some(Planet("Jupiter", 1234567890.01))),
        CreateVertex(id = "saturn", graph = "solar", Some(Planet("Saturn", 1234567890.01))),
        CreateVertex(id = "uranus", graph = "solar", Some(Planet("Uranus", 1234567890.01))),
        CreateVertex(id = "neptune", graph = "solar", Some(Planet("Neptune", 1234567890.01))),
        // regions
        CreateVertex(id = "innerSolarSystem", graph = "solar", Some(Region("Inner Solar System"))),
        CreateVertex(id = "outerSolarSystem", graph = "solar", Some(Region("Outer Solar System"))),
        // moons
        CreateVertex(id = "moon", graph = "solar", Some(Moon("Moon", 1234567890.01))),
        CreateVertex(id = "europa", graph = "solar", Some(Moon("Europa", 1234567890.01))),
        CreateVertex(id = "io", graph = "solar", Some(Moon("Io", 1234567890.01))),
        CreateVertex(id = "enceladus", graph = "solar", Some(Moon("Enceladus", 1234567890.01))),

        // orbiting planets
        CreateEdge(id = "mercuryOrbitsSun", graph = "solar", from = "mercury", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "venusOrbitsSun", graph = "solar", from = "venus", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "earthOrbitsSun", graph = "solar", from = "earth", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "marsOrbitsSun", graph = "solar", from = "mars", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "jupiterOrbitsSun", graph = "solar", from = "jupiter", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "saturnOrbitsSun", graph = "solar", from = "saturn", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "uranusOrbitsSun", graph = "solar", from = "uranus", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "neptuneOrbitsSun", graph = "solar", from = "neptune", to = "sun", bi = false, Some(Orbits(0, 0, 0))),
        // orbiting moons
        CreateEdge(id = "moonOrbitsEarth", graph = "solar", from = "moon", to = "earth", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "europaOrbitsJupiter", graph = "solar", from = "europa", to = "jupiter", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "ioOrbitsJupiter", graph = "solar", from = "io", to = "jupiter", bi = false, Some(Orbits(0, 0, 0))),
        CreateEdge(id = "enceladusOrbitsSaturn", graph = "solar", from = "enceladus", to = "saturn", bi = false, Some(Orbits(0, 0, 0))),
        // regions membership
        CreateEdge(id = "mercuryInInnerSolarSystem", graph = "solar", from = "mercury", to = "innerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "venusInInnerSolarSystem", graph = "solar", from = "venus", to = "innerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "earthInInnerSolarSystem", graph = "solar", from = "earth", to = "innerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "marsInInnerSolarSystem", graph = "solar", from = "mars", to = "innerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "jupiterInOuterSolarSystem", graph = "solar", from = "jupiter", to = "outerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "saturnInOuterSolarSystem", graph = "solar", from = "saturn", to = "outerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "uranusInOuterSolarSystem", graph = "solar", from = "uranus", to = "outerSolarSystem", bi = false, Some(MemberOf())),
        CreateEdge(id = "neptuneInOuterSolarSystem", graph = "solar", from = "neptune", to = "outerSolarSystem", bi = false, Some(MemberOf()))
    )


    case class HygFullRecord(starId: Int,
                             hip: Int,
                             hd: Option[Int],
                             hr: Option[Int],
                             gliese: String,
                             bayerFlamsteed: String,
                             properName: String,
                             ra: Double,
                             dec: Double,
                             dist: Double,
                             mag: Float,
                             absMag: Float,
                             spectrum: String,
                             colorIndex: String)


    object HygFullRecord {

        def parseOption[T](s: String)(f: (String) => T): Option[T] = try { Some(f(s)) } catch { case _: Throwable => None }

        def apply(seq: Seq[ String ]): HygFullRecord = {
            val arr = seq.toArray
            val rec = HygFullRecord(
                starId = arr(0).toInt,
                hip = arr(1).toInt,
                hd = parseOption(arr(2))(_.toInt),
                hr = parseOption(arr(3))(_.toInt),
                gliese = arr(4),
                bayerFlamsteed = arr(5),
                properName = arr(6),
                ra = arr(7).toDouble,
                dec = arr(8).toDouble,
                dist = arr(9).toDouble,
                mag = arr(10).toFloat,
                absMag = arr(11).toFloat,
                spectrum = arr(12),
                colorIndex = arr(13)
            )
            rec
        }

        def cv(rec: HygFullRecord) = CreateVertex(UUID.randomUUID().toString, "hyg", Some(rec))
    }


}
