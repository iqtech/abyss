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

package io.abyss.test.graph

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.{UUID, Date}
import org.junit.{Ignore, Test}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Await, ExecutionContext}

import io.abyss._
import io.abyss.client._
import com.github.tototoshi.csv.CSVReader
import java.io.File


/**
 * Created by cane, 11.06.13 21:20
 */
@Test
class GraphTest {

    val RunAndWaitSeconds = 600
    implicit val ec = ExecutionContext.global
    val uids = scala.collection.mutable.HashSet[ String ]()

    val testVolume = 1000000


    @Test
    def testStartN1() = {
        System.setProperty("config.resource", "/data.conf")
        System.setProperty("akka.remote.netty.tcp.port", "2551")
        runAndWait()
    }


    @Test
    def testStartN2() = {
        System.setProperty("config.resource", "/data.conf")
        System.setProperty("akka.remote.netty.tcp.port", "2552")
        runAndWait()
    }


    @Test
    def testStartN3() = {
        System.setProperty("config.resource", "/front.conf")
        runAndWait()

    }


    @Test
    def testStartN4() = {
        System.setProperty("config.resource", "/front.conf")
        runAndWait()
    }


    @Test
    def testCQRS() = {
        System.setProperty("config.resource", "/client.conf")
        implicit val system = ActorSystem("abyss-test")
        implicit val timeout = Timeout(5 seconds)
        implicit val duration = 2.seconds


        // Graph traversing filters

        val EOrbits = {
            e: EdgeState => e.data.get.isInstanceOf[ Orbits ]
        }

        val VIsPlanet = {
            v: VertexState => v.data.get.isInstanceOf[ Planet ]
        }

        val VIsStar = {
            v: VertexState => v.data.get.isInstanceOf[ Star ]
        }

        val whatStarIsMoonOrbiting = V ++: Array[ Any ](EOrbits, VIsPlanet, EOrbits, VIsStar)
        val whatPlanetIsMoonOrbiting = V ++: Array[ Any ](EOrbits, VIsPlanet)


        // create client working in 'abyss-test' actor system

        val abyssClient: ActorRef = AbyssClient(
            system,
            "client",
            "akka.tcp://abyss@127.0.0.1:2551/user/node",
            "akka.tcp://abyss@127.0.0.1:2552/user/node")

        val vertexIds = collection.mutable.Set.empty[ String ]
        val edgeIds = collection.mutable.Set.empty[ String ]

        var fseq = Seq.empty[ Future[ CommandProcessingResult ] ]


        solarSystem foreach {
            cmd =>
                println("Command %s" format cmd.toString)

                fseq = fseq :+ ( abyssClient ? cmd ).mapTo[ CommandProcessingResult ]

                if ( cmd.isInstanceOf[ CreateVertex ] ) vertexIds += cmd.id
                if ( cmd.isInstanceOf[ CreateEdge ] ) edgeIds += cmd.id
        }

        val futureRes = Future.fold(fseq)(true)(_ && _.isInstanceOf[ CommandProcessed.type ])

        futureRes foreach {
            ok =>
                println("Graph elements creation result: " + ok.toString)
                if ( ok ) {
                    val f = ( abyssClient ? ReadMany(( vertexIds ++ edgeIds ).toSeq, "test") ).mapTo[ Array[ GraphElementState ] ]
                    val res = Await.result(f, duration)
                    println("Elements created:\n" + res.mkString("\n"))

                    // Run query several times
                    println("What star Moon is orbiting? (started at: %s)".format(new Date().toString))

                    1 to 10 foreach {
                        iteration =>
                            println("Iteration %s" format iteration)
                            val traversable = QueryTraversable(UUID.randomUUID().toString, "moon", whatStarIsMoonOrbiting)

                            val whatStarMoonIsOrbiting: Future[ TraversingResult ] = ( abyssClient ? traversable ).mapTo[ TraversingResult ]

                            // in real app the future may be sent back
                            val queryResult = Await.result(whatStarMoonIsOrbiting, duration)
                            println("Moon is orbiting \n" + queryResult.ids.mkString(","))
                    }

                    println("What star Moon is orbiting? (finished at: %s)".format(new Date().toString))
                }
        }


        awaitTest(5)
    }



    @Test
    def testReadHygFull() {

        System.setProperty("config.resource", "/client.conf")
        implicit val system = ActorSystem("abyss-test")
        implicit val timeout = Timeout(5 seconds)
        implicit val duration = 2.seconds

        val abyssClient: ActorRef = AbyssClient(
            system,
            "client",
            "akka.tcp://abyss@127.0.0.1:2551/user/node",
            "akka.tcp://abyss@127.0.0.1:2552/user/node")

        val reader = CSVReader.open(new File("/home/cane/work/git/HYG-Database/hygfull.csv"))
        val it = reader.iterator

        it.next() // skip header

        var fseq = Seq.empty[ Future[ CommandProcessingResult ] ]

        println("reading...")
        var i = 0
        while ( it.hasNext ) {
            val seq = it.next()
            val rec = HygFullRecord(seq)
            i += 1

            fseq = fseq :+ ( abyssClient ? HygFullRecord.cv(rec) ).mapTo[ CommandProcessingResult ]
            //Await.result(f, duration)
            if(i % 1000 == 0) println("  recs processed: %d" format i)

        }

        reader.close()

        val futureRes = Future.fold(fseq)(true)(_ && _.isInstanceOf[ CommandProcessed.type ])

        futureRes foreach {
            ok =>
                println("done: %d" format i)
        }

    }


    @Test
    @Ignore
    def testDataStruct() = {

        val m = new ConcurrentHashMap[ String, Any ](testVolume)
        val a = new collection.mutable.ArrayBuffer[ String ](testVolume)
        val s = new mutable.HashSet[ String ]()

        // Map

        println("write %d to map" format testVolume)
        var t1 = new Date()
        ( 1 to testVolume ).foreach {
            i =>
                m.put("key %d".format(i), "rekord asjdhasdjhas dqweqwieuqowie qowieuq owieuq woeiuq woeiuq woeiquw oeiquweoqiwueqowieuq owieuqoweiuqwoeiqwueqoiweuqowieuqwoeiuq woiwdfgehbrt w837r5293uqjk whfqw ruquwoeqwoeqiweqijwheiqwehorfjgprtohie0rwoeuyr %d".format(i))
        }
        var t2 = new Date()
        var milis = t2.getTime - t1.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))

        println("read %d from map" format testVolume)
        ( 1 to testVolume ).par.foreach {
            i =>
            //m.contains("key %d" format i)
                val v = m.get("key %d" format i)
                val h = v.hashCode % 65536
        }
        var t3 = new Date()
        milis = t3.getTime - t2.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))

        // Array
        println("write %d to array" format testVolume)
        t1 = new Date()
        ( 1 to testVolume ).foreach {
            i =>
                a += "rekord asjdhasdjhas dqweqwieuqowie qowieuq owieuq woeiuq woeiuq woeiquw oeiquweoqiwueqowieuq owieuqoweiuqwoeiqwueqoiweuqowieuqwoeiuq woiwdfgehbrt w837r5293uqjk whfqw ruquwoeqwoeqiweqijwheiqwehorfjgprtohie0rwoeuyr %d".format(i)
        }
        t2 = new Date()
        milis = t2.getTime - t1.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))

        println("read %d from array" format testVolume)
        ( 0 to testVolume - 1 ).par.foreach {
            i =>
                val ss = a(i)
        }
        t3 = new Date()
        milis = t3.getTime - t2.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))


        // Set
        println("write %d to set" format testVolume)
        t1 = new Date()
        ( 1 to testVolume ).foreach {
            i =>
                s += "key %d".format(i)
        }
        t2 = new Date()
        milis = t2.getTime - t1.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))

        println("read %d from set" format testVolume)
        ( 1 to testVolume ).par.foreach {
            i =>
                s.contains("key %d".format(i))
        }
        t3 = new Date()
        milis = t3.getTime - t2.getTime
        println("Finished in %dms for %d rows".format(milis, testVolume))

    }


    @Test
    @Ignore
    def testConfig() = {
        System.setProperty("config.resource", "/data.conf")
        val c = Abyss.system.settings.config
        println(c.getInt("akka.cluster.min-nr-of-members"))
        println(c.getBoolean("abyss.clustered"))
        println(c.getStringList("akka.cluster.roles"))
    }


    @Test
    @Ignore
    def testHashCircle() = {
        //UUID (32 characters - 16 bytes big integer): 8978dccc-8181-4f2a-826b-a10090bb7887
        //val uuid = UUID.randomUUID().toString.replace("-", "")

        val uuid = "ffffffffffffffffffffffffffffffff"
        var key = BigInt(uuid, 16)
        key = key >> 64
        println(key.toString(16))
    }


    @Test
    @Ignore
    def vectorClockTest() = {
        //		val v0 = new VectorClock()
        //		println(v0.timestamp.toString())
        //
        //		val v1 = new VectorClock()
        //		println(v1.timestamp.toString())
        //
        //		println(v0.timestamp < v1.timestamp)
    }


    private def runAndWait() = {
        val system = Abyss.system
        awaitAbyss(RunAndWaitSeconds)
        println("Closing Actor System {}", system.name)
        system.shutdown()
    }

    private def awaitAbyss(s: Int) = try {
        val timeout = Timeout(s seconds)
        val duration = timeout.duration
        Abyss.system.awaitTermination(duration)
    } catch {
        case e: Throwable => println("EOT -> %s" format e.getMessage)
    }


    private def awaitTest(s: Int)(implicit system: ActorSystem) = try {
        val timeout = Timeout(s seconds)
        val duration = timeout.duration
        system.awaitTermination(duration)
    } catch {
        case e: Throwable => println("EOT -> %s" format e.getMessage)
    }

}
