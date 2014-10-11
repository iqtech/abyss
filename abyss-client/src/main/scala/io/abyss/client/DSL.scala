package io.abyss.client

import java.util.UUID

// Created by cane, 04.10.14 19:58

/**
 * Simple DSL which creates sequence of filters used by graph traversing.
 */
object DSL {

	trait ValueLike {

		// TODO requires information about graph (G), element type (V|E) and key (K) -> (G:V:K) as unique id

		/**
		 * Tests equality of two elements. Implementation should always use indices, so index must
		 * be defined per search key.
		 * @param v
		 * @return
		 */
		def ===(v: Any): Boolean = ???

		def >(v: Any): Boolean = ???

		def <(v: Any): Boolean = ???

	}

	trait ElementLike {
		def apply(key: String): ValueLike
	}

	case class Q( name: String = UUID.randomUUID( ).toString, seq: Array[F] = Array.empty ) {

		/**
		 * Selects vertices using given selector
		 * @return new, updated query
		 */
		def v( f: F ): Q = {
			//find vertex
			copy(seq = seq :+ f)
		}

		/**
		 * Selects edges using given selector
		 * @param f
		 * @return
		 */
		def e( f: F ): Q = {
			// find edge
			copy(seq = seq :+ f)
		}


		//def inE: Q = inE( _ => true )

		//def outE( f: F ): Q = ???

		def inE(ec: String)( f: F ): Q = ???

		def outE(ec: String)(f: F): Q = ???

		def inV: Q = ???

		def outV: Q = ???

		def apply( k: String ): Any = ???

		def back( p: String ): Q = ???

		def option( p: String ): Q = ???

		def as( p: String ): Q = ???

		def get: Q = ???

		def branch(q: Q): Q = ???

		def addFilter( f: F ): Unit = {
			// TODO
		}
	}

	object V {
		def apply(): Q = Q()
		def apply(f: F): Q = Q().v(f)
	}

	object E {
		def apply(): Q = Q()
		def apply(f: F): Q = Q().e(f)
	}

	type F = ( ElementLike ) => Boolean

	object Test {
		val root = V( _("id") === "root" )
		val translation = V().outE("hasTranslation")(_("lang") === "pl").outV
		val contentTreeChildren = V().outE("hasContent")(_=>true).outV

		val q = root as "root" branch translation back "root" branch contentTreeChildren option "root"
	}

}
