package com.spingo.op_rabbit

import com.rabbitmq.client.Address
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultSaslConfig
import com.rabbitmq.client.ExceptionHandler
import com.rabbitmq.client.SaslConfig
import com.rabbitmq.client.impl.DefaultExceptionHandler
import com.typesafe.config.Config
import javax.net.SocketFactory
import scala.collection.JavaConverters._
import scala.util.Try

/** Because topology recovery strategy configuration is crucial to how op-rabbit works, we don't allow some options to
  * be specified
  *
  * Modeling the allowed options via a case-class allows the compiler to tell the library user which options aren't
  * allowed.
  */
case class ConnectionParams(
  hosts: Seq[Address] = Seq(new Address(ConnectionFactory.DEFAULT_HOST, ConnectionFactory.DEFAULT_AMQP_PORT)),
  username: String = ConnectionFactory.DEFAULT_USER,
  password: String = ConnectionFactory.DEFAULT_PASS,
  virtualHost: String = ConnectionFactory.DEFAULT_VHOST,
  ssl: Boolean = false,
  // Replace the table of client properties that will be sent to the server during subsequent connection startups.
  clientProperties: Map[String, Object] = Map.empty[String, Object],

  /** Warning - setting this to 0 causes problems when connecting a cluster; if the first host is down, then further
    * hosts may not be tried.
    */
  connectionTimeout: Int = 10000 /* 10 seconds */,
  exceptionHandler: ExceptionHandler = new DefaultExceptionHandler(),
  requestedChannelMax: Int = ConnectionFactory.DEFAULT_CHANNEL_MAX,
  requestedFrameMax: Int = ConnectionFactory.DEFAULT_FRAME_MAX,
  requestedHeartbeat: Int = ConnectionFactory.DEFAULT_HEARTBEAT,
  saslConfig: SaslConfig = DefaultSaslConfig.PLAIN,
  sharedExecutor: Option[java.util.concurrent.ExecutorService] = None,
  shutdownTimeout: Int = ConnectionFactory.DEFAULT_SHUTDOWN_TIMEOUT,
  socketFactory: SocketFactory = SocketFactory.getDefault
) {
  // TODO - eliminate ClusterConnectionFactory after switching to use RabbitMQ's topology recovery features.
  protected [op_rabbit] def applyTo(factory: ClusterConnectionFactory): Unit = {
    factory.setHosts(hosts.toArray)
    factory.setUsername(username)
    factory.setPassword(password)
    factory.setVirtualHost(virtualHost)
    // Replace the table of client properties that will be sent to the server during subsequent connection startups.
    factory.setClientProperties(clientProperties.asJava)
    factory.setConnectionTimeout(connectionTimeout)
    factory.setExceptionHandler(exceptionHandler)
    factory.setRequestedChannelMax(requestedChannelMax)
    factory.setRequestedFrameMax(requestedFrameMax)
    factory.setRequestedHeartbeat(requestedHeartbeat)
    factory.setSaslConfig(saslConfig)
    sharedExecutor foreach factory.setSharedExecutor
    factory.setShutdownTimeout(shutdownTimeout)
    factory.setSocketFactory(socketFactory)
    if (ssl) factory.useSslProtocol()
  }
}

object ConnectionParams {
  private def readHosts(config: Config): Seq[String] = {
    Try(config.getStringList("hosts").asScala).getOrElse(readCommaSeparatedHosts(config))
  }

  private def readCommaSeparatedHosts(config: Config): Seq[String] =
    config
      .getString("hosts")
      .split(",")
      .map(_.trim)

  def fromConfig(config: Config = RabbitConfig.connectionConfig) = {
    val hosts = readHosts(config)
    val port = config.getInt("port")

    def calcAddress ( hostDef: String) =
      if(hostDef.contains(':')) {
        val hp = hostDef.split(":").take(2)
        new Address(hp(1),hp(2).toInt)
      } else { new Address(hostDef, port) }

    ConnectionParams(
      hosts = hosts.map { h => calcAddress(h) },
      connectionTimeout = config.getDuration("connection-timeout", java.util.concurrent.TimeUnit.MILLISECONDS).toInt,
      username = config.getString("username"),
      password = config.getString("password"),
      virtualHost = config.getString("virtual-host"),
      ssl = config.getBoolean("ssl")
    )
  }
}
