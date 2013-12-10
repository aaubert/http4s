package org.http4s
package netty
package spdy

import scala.util.control.Exception.allCatch

import java.net.{URI, InetSocketAddress}
import java.util.concurrent.ConcurrentHashMap

import io.netty.handler.codec.spdy._
import io.netty.channel.{Channel, ChannelFutureListener, ChannelHandlerContext}
import io.netty.buffer.ByteBuf

import scalaz.concurrent.Task

import org.http4s.util.middleware.PushSupport
import org.http4s._
import org.http4s.netty.NettySupport
import org.http4s.Response
import org.http4s.netty.utils.SpdyStreamManager
import scala.concurrent.{ExecutionContext, Future}


/**
* @author Bryce Anderson
*         Created on 11/28/13
*/
final class NettySpdyServerHandler(srvc: HttpService,
                  val spdyversion: Int,
                  val localAddress: InetSocketAddress,
                  val remoteAddress: InetSocketAddress,
                  val ec: ExecutionContext)
          extends NettySupport[SpdyFrame, SpdySynStreamFrame]
          with SpdyStreamManager[NettySpdyStream]
          with SpdyInboundWindow
          with SpdyConnectionOutboundWindow {

  import NettySupport._

  private var _ctx: ChannelHandlerContext = null

  def ctx = _ctx

  val serverSoftware = ServerSoftware("HTTP4S / Netty / SPDY")

  val service = PushSupport(srvc)

  def isServer = true

  def connectionWriteBodyChunk(streamid: Int, chunk: BodyChunk, flush: Boolean): Future[Channel] = {
    if (flush) ctx.writeAndFlush(new DefaultSpdyDataFrame(streamid, chunkToBuff(chunk)))
    else ctx.write(new DefaultSpdyDataFrame(streamid, chunkToBuff(chunk)))
  }

  def connectWriteStreamEnd(streamid: Int, chunk: BodyChunk, t: Option[TrailerChunk]): Future[Channel] = {
    t.fold{
      val msg = new DefaultSpdyDataFrame(streamid, chunkToBuff(chunk))
      msg.setLast(true)
      ctx.writeAndFlush(msg)
    }{ t =>
      val buff = chunkToBuff(chunk)
      if (buff.readableBytes() > 0) ctx.write(new DefaultSpdyDataFrame(streamid, buff))
      val msg = new DefaultSpdyHeadersFrame(streamid)
      t.headers.foreach( h => msg.headers().add(h.name.toString, h.value) )
      ctx.writeAndFlush(msg)
    }
  }

  override def channelRegistered(ctx: ChannelHandlerContext) {
    _ctx = ctx
  }

  def closeSpdyOutboundWindow(): Unit = {
    foreachStream { s =>
      s.closeSpdyOutboundWindow()
      s.close()
    }
    ctx.close()
  }

  override protected def toRequest(ctx: ChannelHandlerContext, req: SpdySynStreamFrame): Request = {
    val uri = new URI(SpdyHeaders.getUrl(spdyversion, req))
    val scheme = Option(SpdyHeaders.getScheme(spdyversion, req)).getOrElse{
      logger.warn(s"${remoteAddress}: Request doesn't have scheme header")
      "https"
    }

    val servAddr = ctx.channel.remoteAddress.asInstanceOf[InetSocketAddress]
    val replyStream = new NettySpdyServerReplyStream(req.getStreamId, ctx, this, initialWindow)

    if (!putStream(replyStream)) {
      throw new InvalidStateException("Received two SpdySynStreamFrames " +
                                     s"with same id: ${replyStream.streamid}")
    }

    Request(
      requestMethod = Method(SpdyHeaders.getMethod(spdyversion, req).name),
      //scriptName = contextPath,
      pathInfo = uri.getRawPath,
      queryString = uri.getRawQuery,
      protocol = getProtocol(req),
      headers = toHeaders(req.headers),
      urlScheme = HttpUrlScheme(scheme),
      serverName = servAddr.getHostName,
      serverPort = servAddr.getPort,
      serverSoftware = serverSoftware,
      remote = remoteAddress.getAddress, // TODO using remoteName would trigger a lookup
      body = makeProcess(replyStream.chunkHandler)
    )
  }

  override protected def renderResponse(ctx: ChannelHandlerContext, req: SpdySynStreamFrame, response: Response): Task[List[_]] = {
    val handler = getStream(req.getStreamId)
    if (handler != null)  {
      assert(handler.isInstanceOf[NettySpdyServerReplyStream])   // Should only get requests to ReplyStreams
      handler.asInstanceOf[NettySpdyServerReplyStream].handleRequest(req, response)
    }
    else sys.error("Newly created stream doesn't exist! How can this be?")
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      logger.error(s"Exception on connection with $remoteAddress", cause)
      killStreams(cause)
      if (ctx.channel().isOpen) {  // Send GOAWAY frame to signal disconnect if we are still connected
        val goaway = new DefaultSpdyGoAwayFrame(lastOpenedStream, 2) // Internal Error
        allCatch(ctx.writeAndFlush(goaway).addListener(ChannelFutureListener.CLOSE))
      }
    } catch {    // Don't end up in an infinite loop of exceptions
      case t: Throwable =>
        val causestr = if (cause != null) cause.getStackTraceString else "NULL."
        logger.error("Caught exception in exception handling: " + causestr, t)
    }
  }

  /** Forwards messages to the appropriate SpdyStreamContext
    *
    * @param msg SpdyStreamFrame to be forwarded
    */
  private def forwardMsg(msg: SpdyStreamFrame) {
    val handler = getStream(msg.getStreamId)

    // Deal with data windows
    if (msg.isInstanceOf[SpdyDataFrame]) {
      val m = msg.asInstanceOf[SpdyDataFrame]
      decrementWindow(m.content().readableBytes())

      if (handler == null) incrementWindow(m.content().readableBytes())
    }

    if (handler!= null) handler.handle(msg)
    else {
      logger.debug(s"Received chunk on unknown stream ${msg.getStreamId}")
      val rst = new DefaultSpdyRstStreamFrame(msg.getStreamId, 5)  // 5: Cancel the stream
      ctx.writeAndFlush(rst)
    }
  }

  /** deal with incoming messages which belong to this service
    * @param ctx ChannelHandlerContext of the pipeline
    * @param msg received message
    */
  def onHttpMessage(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case req: SpdySynStreamFrame =>
      logger.trace(s"Received Request frame with id ${req.getStreamId}")
      setCurrentStreamID(req.getStreamId)
      runHttpRequest(ctx, req)

    case msg: SpdyStreamFrame => forwardMsg(msg)

    case msg: SpdyWindowUpdateFrame =>
      if (msg.getStreamId == 0) updateOutboundWindow(msg.getDeltaWindowSize)  // Global window size
      else {
        val handler = getStream(msg.getStreamId)
        if (handler != null) handler.handle(msg)
        else  {
          logger.debug(s"Received chunk on stream ${msg.getStreamId}: no handler.")
          val rst = new DefaultSpdyRstStreamFrame(msg.getStreamId, 5)  // 5: Cancel the stream
          ctx.writeAndFlush(rst)
        }
      }

    case p: SpdyPingFrame =>
      if (p.getId % 2 == 1) {   // Must ignore Pings with even number id
        logger.trace(s"Sending ping reply frame with id ${p.getId}")
        val ping = new DefaultSpdyPingFrame(p.getId)
        ctx.writeAndFlush(ping)
      }

    case s: SpdySettingsFrame => handleSpdySettings(s)

    case msg => logger.warn("Received unknown message type: " + msg + ". Dropping.")
  }

  protected def submitDeltaInboundWindow(n: Int): Unit = {
    logger.trace(s"Updating Connection inbound window by $n bytes")
    ctx.writeAndFlush(new DefaultSpdyWindowUpdateFrame(0, n))
  }

  private def handleSpdySettings(settings: SpdySettingsFrame) {
    import SpdySettingsFrame._
    logger.trace(s"Received SPDY settings frame: $settings")

    val maxStreams = settings.getValue(SETTINGS_MAX_CONCURRENT_STREAMS)
    if (maxStreams > 0) setMaxStreams(maxStreams)

    val newWindow = settings.getValue(SETTINGS_INITIAL_WINDOW_SIZE)
    // TODO: Deal with window sizes and buffering. http://dev.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3#TOC-2.6.8-WINDOW_UPDATE
    if (newWindow > 0) {
      // Update the connection window sizes
      setInitialWindow(newWindow)
      changeMaxInboundWindow(newWindow)

      // Update the connection windows of any streams
      val diff = newWindow - initialWindow
      foreachStream{ s =>
        s.updateOutboundWindow(diff)
          s.asInstanceOf[SpdyInboundWindow].changeMaxInboundWindow(diff)
      }
    }
  }

  // TODO: Need to implement a Spdy HttpVersion
  private def getProtocol(req: SpdySynStreamFrame) = ServerProtocol.`HTTP/1.1`
}