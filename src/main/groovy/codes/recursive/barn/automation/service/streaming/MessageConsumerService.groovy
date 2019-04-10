package codes.recursive.barn.automation.service.streaming

import codes.recursive.barn.automation.event.EventEmitter
import codes.recursive.barn.automation.model.BarnEvent
import codes.recursive.barn.automation.service.data.OracleDataService
import codes.recursive.barn.automation.util.ArduinoMessage
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.streaming.StreamClient
import com.oracle.bmc.streaming.model.CreateGroupCursorDetails
import com.oracle.bmc.streaming.model.Message
import com.oracle.bmc.streaming.requests.CreateGroupCursorRequest
import com.oracle.bmc.streaming.requests.GetMessagesRequest
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

@ApplicationScoped
class MessageConsumerService {
    private static final Logger log = Logger.getLogger(MessageConsumerService.class.name)

    String configFilePath
    String streamId
    String groupName = 'group-0'
    StreamClient client
    private final AtomicBoolean closed = new AtomicBoolean(false)

    @Inject private EventEmitter eventEmitter
    @Inject private OracleDataService oracleDataService


    MessageConsumerService(configFilePath=System.getProperty("ociConfigPath", "/.oci/config"), streamId=System.getProperty("outgoingStreamId")) {
        this.configFilePath = configFilePath
        this.streamId = streamId
        def provider =  new ConfigFileAuthenticationDetailsProvider(this.configFilePath, 'DEFAULT')
        def client = new StreamClient(provider)
        client.setRegion('us-phoenix-1')
        this.client = client
    }


    void start() {
        log.info("Creating cursor...")

        def cursorDetails = CreateGroupCursorDetails.builder()
                .type(CreateGroupCursorDetails.Type.TrimHorizon)
                .commitOnGet(true)
                .groupName(this.groupName)
                .build()
        def groupCursorRequest = CreateGroupCursorRequest.builder()
                .streamId(streamId)
                .createGroupCursorDetails(cursorDetails)
                .build()

        def cursorResponse = this.client.createGroupCursor(groupCursorRequest)

        log.info("Cursor created...")

        def getRequest = GetMessagesRequest.builder()
                .cursor(cursorResponse.cursor.value)
                .streamId(this.streamId)
                .build()

        while(!closed.get()) {
            def getResult = this.client.getMessages(getRequest)
            getResult.items.each { Message record ->
                def msg
                try {
                    def slurper = new JsonSlurper()
                    msg = slurper.parseText( new String(record.value, "UTF-8") )
                    log.info "Received: ${JsonOutput.toJson(msg)}"
                    BarnEvent evt = new BarnEvent( msg?.type, JsonOutput.toJson(msg?.data), record.timestamp )
                    if( evt.type != ArduinoMessage.CAMERA_0 ) {
                        eventEmitter.emit('incomingMessage', [message: [type: evt.type, capturedAt: evt.capturedAt, data: slurper.parseText(evt.data)], timestamp: record.timestamp])
                    }
                    oracleDataService.save(evt)
                }
                catch (JsonException e) {
                    log.warning("Error parsing JSON from ${record.value}")
                    e.printStackTrace()
                }
                catch (Exception e) {
                    log.warning("Error:")
                    e.printStackTrace()
                }
            }
            getRequest.cursor = getResult.opcNextCursor
            sleep(500)
        }

    }

    def close() {
        log.info("Closing consumer...")
        closed.set(true)
    }
}
