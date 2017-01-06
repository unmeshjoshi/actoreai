package cswdemo

import java.net.{InetAddress, URI}
import java.util.Optional
import javax.jmdns.{JmDNS, ServiceEvent, ServiceInfo, ServiceListener}

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorPath, ActorRef, ActorSystem, Address, ExtendedActorSystem, Extension, ExtensionKey, Identify, Props, Terminated}
import cswdemo.Connection.{AkkaConnection, HttpConnection, TcpConnection}

import collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{Future, Promise}


object LocationService {

  private val dnsType = "_csw._tcp.local."
  // -- Keys used to store values in DNS records --
  // URI path part
  private val PATH_KEY = "path"
  // Akka system name
  private val SYSTEM_KEY = "system"
  // Indicates the part of a command service config that this service is interested in
  private val PREFIX_KEY = "prefix"

  private lazy val registry = getRegistry()


  def registerAkkaConnection(componentId: ComponentId, actorRef: ActorRef, prefix: String = "")(implicit system: ActorSystem): Future[RegistrationResult] = {
    import system.dispatcher
    val connection = AkkaConnection(componentId)
    Future {
      val uri = getActorUri(actorRef, system)
      val values = Map(
        PATH_KEY -> uri.getPath,
        SYSTEM_KEY -> uri.getUserInfo,
        PREFIX_KEY -> prefix
      )
      val service = ServiceInfo.create(dnsType, connection.toString, uri.getPort, 0, 0, values.asJava)
      registry.registerService(service)
      RegisterResult(registry, service, componentId)
    }
  }


  // --- Used to get the full path URI of an actor from the actorRef ---
  private class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
    def address: Address = system.provider.getDefaultAddress
  }

  private object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]


  // Gets the full URI for the actor
  private def getActorUri(actorRef: ActorRef, system: ActorSystem): URI =
    new URI(actorRef.path.toStringWithAddress(RemoteAddressExtension(system).address))

  private def getRegistry(): JmDNS = {
    val hostname = Option(System.getProperty("akka.remote.artery.canonical.hostname"))
    val registry = if (hostname.isDefined) {
      val addr = InetAddress.getByName(hostname.get)
      JmDNS.create(addr, hostname.get)
    } else {
      JmDNS.create()
    }
    sys.addShutdownHook(registry.close())
    registry
  }


  /**
    * Returned from register calls so that client can close the connection and deregister the service
    */
  trait RegistrationResult {
    def unregister(): Unit

    val componentId: ComponentId
  }

  private case class RegisterResult(registry: JmDNS, info: ServiceInfo, componentId: ComponentId) extends RegistrationResult {
    override def unregister(): Unit = registry.unregisterService(info)
  }


  sealed trait Location {
    def connection: Connection

    val isResolved: Boolean = false
    val isTracked: Boolean = true
  }

  final case class UnTrackedLocation(connection: Connection) extends Location {
    override val isTracked = false
  }


  case class TrackConnection(connection: Connection)

  case class UntrackConnection(connection: Connection)


  final case class Unresolved(connection: Connection) extends Location

  final case class ResolvedAkkaLocation(connection: AkkaConnection, uri: URI, prefix: String = "", actorRef: Option[ActorRef] = None) extends Location {
    override val isResolved = true

    /**
      * Java constructor
      */
    def this(connection: AkkaConnection, uri: URI, prefix: String, actorRef: Optional[ActorRef]) = this(connection, uri, prefix, actorRef.asScala)

    /**
      * Java API to get actorRef
      * @return
      */
    def getActorRef: Optional[ActorRef] = actorRef.asJava
  }

  final case class ResolvedHttpLocation(connection: HttpConnection, uri: URI, path: String) extends Location {
    override val isResolved = true
  }

  final case class ResolvedTcpLocation(connection: TcpConnection, host: String, port: Int) extends Location {
    override val isResolved = true
  }


  object LocationTracker {
    /**
      * Used to create the LocationTracker actor
      *
      * @param replyTo optional actorRef to reply to (default: parent of this actor)
      */
    def props(replyTo: Option[ActorRef] = None): Props = Props(classOf[LocationTracker], replyTo)
  }
  case class LocationTracker(replyTo: Option[ActorRef]) extends Actor with ActorLogging with ServiceListener {

    // Set of resolved services (Needs to be a var, since the ServiceListener callbacks prevent using akka state)
    // Private loc is for testing
    private var connections = Map.empty[Connection, Location]

    // The future for this promise completes the next time the serviceResolved() method is called
    private var updateInfo = Promise[Unit]()

    registry.addServiceListener(dnsType, this)

    override def postStop: Unit = {
      registry.removeServiceListener(dnsType, this)
    }

    override def serviceAdded(event: ServiceEvent): Unit = {
      println("Service added  " + event)
      val connection = Connection(event.getName)
      connection.map { connection =>
        if (!connections.contains(connection)) {
          val unc = UnTrackedLocation(connection)
          connections += connection -> unc
          // Should we send an update here?
        }
      }
    }

    override def serviceRemoved(event: ServiceEvent): Unit = {
      log.debug(s"Service Removed Listener: ${event.getName}")
      Connection(event.getInfo.getName).map(removeService)
    }

    // Removes the given service
    // If it isn't in our map, we don't care since it's not being tracked
    // If it is Unresolved, it's still unresolved
    // If it is resolved, we update to unresolved and send a message to the client
    private def removeService(connection: Connection): Unit = {
      def rm(loc: Location): Unit = {
        if (loc.isResolved) {
          val unc = Unresolved(loc.connection)
          connections += (loc.connection -> unc)
          sendLocationUpdate(unc)
        }
      }

      connections.get(connection).foreach(rm)
    }

    // Check to see if a connection is already resolved, and if so, resolve the service
    private def tryToResolve(connection: Connection): Unit = {
      connections.get(connection) match {
        case Some(Unresolved(_)) =>
          val s = Option(registry.getServiceInfo(dnsType, connection.toString))
          s.foreach(resolveService(connection, _))
        case x =>
          log.warning(s"Attempt to track and already tracked connection: $x")
      }
    }

    override def serviceResolved(event: ServiceEvent): Unit = {
      // Complete the promise so that the related future completes, in case the WaitToTrack() method is waiting for it
      updateInfo.success(())
      updateInfo = Promise[Unit]()
    }

    private def resolveService(connection: Connection, info: ServiceInfo): Unit = {
      try {
        // Gets the URI, adding the akka system as user if needed
        def getUri(uriStr: String): Option[URI] = {
          connection match {
            case _: AkkaConnection =>
              val path = info.getPropertyString(PATH_KEY)
              if (path == null) None else getAkkaUri(uriStr, info.getPropertyString(SYSTEM_KEY))
            case _ =>
              Some(new URI(uriStr))
          }
        }

        info.getURLs(connection.connectionType.name).toList.flatMap(getUri).foreach {
          uri =>
            connection match {
              case ac: AkkaConnection =>
                val prefix = info.getPropertyString(PREFIX_KEY)
                // An Akka connection is finished after identify returns
                val rac = ResolvedAkkaLocation(ac, uri, prefix)
                identify(rac)
              case hc: HttpConnection =>
                // An Http connection is finished off here
                val path = info.getPropertyString(PATH_KEY)
                val rhc = ResolvedHttpLocation(hc, uri, path)
                connections += (connection -> rhc)
                log.debug(s"Resolved HTTP: ${connections.values.toList}")
                // Here is where the resolved message is sent for an Http Connection
                sendLocationUpdate(rhc)
              case tcp: TcpConnection =>
                // A TCP-based connection is ended here
                val rtc = ResolvedTcpLocation(tcp, uri.getHost, uri.getPort)
                connections += (connection -> rtc)
                sendLocationUpdate(rtc)
            }
        }
      } catch {
        case e: Exception => log.error(e, "resolveService: resolve error")
      }
    }

    private def getAkkaUri(uriStr: String, userInfo: String): Option[URI] = try {
      val uri = new URI(uriStr)
      Some(new URI("akka.tcp", userInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment))
    } catch {
      case e: Exception =>
        // some issue with ipv6 addresses?
        log.error(s"Couldn't make URI from $uriStr and userInfo $userInfo", e)
        None
    }

    // Sends an Identify message to the URI for the actor, which should result in an
    // ActorIdentity reply containing the actorRef.
    private def identify(rs: ResolvedAkkaLocation): Unit = {
      log.debug(s"Attempting to identify actor ${rs.uri.toString}")
      val actorPath = ActorPath.fromString(rs.uri.toString)
      context.actorSelection(actorPath) ! Identify(rs)
    }

    // Called when an actor is identified.
    // Update the resolved map and check if we have everything that was requested.
    private def actorIdentified(actorRefOpt: Option[ActorRef], rs: ResolvedAkkaLocation): Unit = {
      if (actorRefOpt.isDefined) {
        log.debug(s"Resolved: Identified actor $actorRefOpt")
        // Update the table
        val newrc = rs.copy(actorRef = actorRefOpt)
        connections += (rs.connection -> newrc)
        // Watch the actor for death
        context.watch(actorRefOpt.get)
        // Here is where the resolved message is sent for an Akka Connection
        sendLocationUpdate(newrc)
      } else {
        log.warning(s"Could not identify actor for ${rs.connection} ${rs.uri}")
      }
    }

    private def sendLocationUpdate(location: Location): Unit = {
      replyTo.getOrElse(context.parent) ! location
    }

    def waitToTrack(connection: Connection): Unit = {
      import context.dispatcher
      updateInfo.future.onComplete { _ =>
        self ! TrackConnection(connection: Connection)
      }
    }

    // Receive messages
    override def receive: Receive = {

      // Result of sending an Identify message to the actor's URI (actorSelection)
      case ActorIdentity(id, actorRefOpt) =>
        id match {
          case rs: ResolvedAkkaLocation =>
            actorIdentified(actorRefOpt, rs)
          case _ => log.warning(s"Received unexpected ActorIdentity id: $id")
        }

      case TrackConnection(connection: Connection) =>
        // This is called from outside, so if it isn't in the tracking list, add it
        log.debug("----------------Received track connection: " + connection)
        if (!connections.contains(connection)) {
          waitToTrack(connection)
        } else {
          // In this case, there is some entry already in our table, meaning at least serviceAdded has been called
          // There is a chance that it has already been resolved since this is shared across the JVM?
          connections(connection) match {
            case UnTrackedLocation(_) =>
              val unc = Unresolved(connection)
              connections += (connection -> unc)
              tryToResolve(connection)
            case u: Unresolved =>
              log.error("Should not have an Unresolved connection when initiating tracking: " + u)
            case r @ _ =>
              sendLocationUpdate(r)
          }
        }

      case UntrackConnection(connection: Connection) =>
        // This is called from outside, so if it isn't in the tracking list, ignore it
        if (connections.contains(connection)) {
          // Remove from the map and send an updated Resolved List
          connections += (connection -> UnTrackedLocation(connection))
          // Send Untrack back so state can be updated
          replyTo.getOrElse(context.parent) ! UnTrackedLocation(connection)
        }

      case Terminated(actorRef) =>
        // If a requested Akka service terminates, remove it, just in case it didn't unregister with mDns...
        connections.values.foreach {
          case ResolvedAkkaLocation(c, _, _, Some(otherActorRef)) =>
            log.debug(s"Unresolving terminated actor: $c")
            if (actorRef == otherActorRef) removeService(c)
          case x => // should not happen
            log.warning(s"Received Terminated message from unknown location: $x")
        }

      case x =>
        log.error(s"Received unexpected message $x")
    }

  }

}
