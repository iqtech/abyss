package io.abyss.client

// Created by cane, 04.10.14 19:58


object DSL {

	trait GE {
		def apply(k: String): Any
		def back(p: String): GE
		def option(p: String): GE
	}

	type F = ( GE ) => Boolean

	def f(ge: GE): Boolean = {
		true
	}

	trait E extends GE {
		def inV: V
		def outV: V
	}

	trait V extends GE {
		def inE( f: F ): E

		def inE: E = inE(_=>true)

		def outE( f: F ): E

		def outE: E = outE(_=>true)

		def as(p: String): V
	}


	object G {
		def apply(f: F): V = ???
	}

	object V {
		def apply(f: F): V = G(f)
	}


	object Test {
		val q = V(f).as("main").inE.outV.outE.outV.back("main")
	}

}
