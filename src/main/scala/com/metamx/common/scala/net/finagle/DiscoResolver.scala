package com.metamx.common.scala.net.finagle

import com.metamx.common.lifecycle.Lifecycle
import com.metamx.common.scala.Logging
import com.metamx.common.scala.net.curator.Disco
import com.twitter.finagle.{Addr, Address, Resolver}
import com.twitter.util.{Closable, Future, Time, Var}
import java.net.{InetSocketAddress, SocketAddress}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.x.discovery.details.ServiceCacheListener

import scala.collection.JavaConverters._

/**
 * Bridges Finagle with Curator-based service discovery.
 *
 * @param disco Service discovery environment
 */
class DiscoResolver(disco: Disco) extends Resolver with Logging
{
  val scheme = "disco"

  def bind(service: String) = Var.async[Addr](Addr.Pending) {
    updatable =>
      val lifecycle = new Lifecycle
      val serviceCache = disco.cacheFor(service, lifecycle)
      def doUpdate() {
        val newInstances = serviceCache.getInstances.asScala.toSet
        log.info("Updating instances for service[%s] to %s", service, newInstances)
        val newSocketAddresses: Set[Address] = newInstances map
          (instance => Address(new InetSocketAddress(instance.getAddress, instance.getPort)))
        updatable.update(Addr.Bound(newSocketAddresses))
      }
      serviceCache.addListener(
        new ServiceCacheListener
        {
          def cacheChanged() {
            doUpdate()
          }

          def stateChanged(curator: CuratorFramework, state: ConnectionState) {
            doUpdate()
          }
        }
      )
      lifecycle.start()
      try {
        doUpdate()
        new Closable
        {
          def close(deadline: Time) = Future {
            log.info("No longer monitoring service[%s]", service)
            lifecycle.stop()
          }
        }
      }
      catch {
        case e: Exception =>
          log.warn(e, "Failed to bind to service[%s]", service)
          lifecycle.stop()
          throw e
      }
  }
}
