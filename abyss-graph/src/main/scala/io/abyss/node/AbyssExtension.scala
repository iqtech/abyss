package io.abyss.node

import akka.actor.ActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit

class SettingsImpl( config: Config ) extends Extension {
	val DbUri: String = config.getString( "myapp.db.uri" )
	val CircuitBreakerTimeout: Duration =
		Duration( config.getMilliseconds( "myapp.circuit-breaker.timeout" ),
			TimeUnit.MILLISECONDS )
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

	override def lookup = Settings

	override def createExtension( system: ExtendedActorSystem ) =
		new SettingsImpl( system.settings.config )

	/**
	 * Java API: retrieve the Settings extension for the given system.
	 */
	override def get( system: ActorSystem ): SettingsImpl = super.get( system )
}
