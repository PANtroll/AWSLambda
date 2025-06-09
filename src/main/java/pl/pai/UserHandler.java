package pl.pai;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.util.Map;
import java.util.UUID;

public class UserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public static final String NAME = "name";
    private static final String USERS_TABLE = "Users";
    public static final String ID = "id";
    public static final String EMAIL = "email";
    private final DynamoDbClient db = DynamoDbClient.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SesClient sesClient = SesClient.builder().region(Region.EU_NORTH_1).build();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent req, Context context) {
        try {
            String method = req.getHttpMethod();
            String id = req.getPathParameters() != null ? req.getPathParameters().get(ID) : null;
            if ("GET".equals(method) && id == null) {
                return response(200, """
                        {"message": "HELLO WORLD!",
                        "method": "%s",
                        "id": "%s"
                        }
                        """.formatted(method, id));
            }
            if (method == null) {
                return response(500, "no method");
            }
            return switch (method) {
                case "GET" -> getUser(id);
                case "POST" -> createUser(req.getBody());
                case "PUT" -> updateUser(id, req.getBody());
                case "DELETE" -> deleteUser(id);
                default -> response(405, "Method Not Allowed");
            };

        } catch (Exception e) {
            return response(500, e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent getUser(String id) throws Exception {
        User user = getUserFormDB(id);
        if (user == null) {
            return response(404, "User not found");
        }
        return response(200, mapper.writeValueAsString(user));
    }

    private APIGatewayProxyResponseEvent createUser(String body) throws Exception {
        User user = mapper.readValue(body, User.class);
        user.setId(UUID.randomUUID().toString());

        db.putItem(PutItemRequest.builder()
                .tableName(USERS_TABLE)
                .item(Map.of(
                        ID, AttributeValue.fromS(user.getId()),
                        NAME, AttributeValue.fromS(user.getName()),
                        EMAIL, AttributeValue.fromS(user.getEmail())
                ))
                .build());

        String subject = "Nowy użytkownik dodany";
        String emailBody = "Nowy użytkownik: " + user.getName() + "\n" + user.getId();
        SendEmailRequest request = createEmail(user, subject, emailBody);

        sesClient.sendEmail(request);

        return response(201, mapper.writeValueAsString(user));
    }

    private SendEmailRequest createEmail(User user, String subject, String body) {
        return SendEmailRequest.builder()
                .source("s7896@atins.pl")
                .destination(Destination.builder()
                        .toAddresses(user.getEmail())
                        .build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).build())
                        .body(Body.builder()
                                .text(Content.builder().data(body).build())
                                .build())
                        .build())
                .build();
    }

    private APIGatewayProxyResponseEvent updateUser(String id, String body) throws Exception {
        User user = mapper.readValue(body, User.class);
        user.setId(id);

        User oldData = getUserFormDB(id);
        if (oldData == null) {
            return response(404, "User not found");
        }

        db.updateItem(UpdateItemRequest.builder()
                .tableName(USERS_TABLE)
                .key(Map.of(ID, AttributeValue.fromS(id)))
                .attributeUpdates(Map.of(
                        NAME,
                        AttributeValueUpdate.builder().value(AttributeValue.fromS(user.getName())).action(AttributeAction.PUT).build(),
                        EMAIL,
                        AttributeValueUpdate.builder().value(AttributeValue.fromS(user.getEmail())).action(AttributeAction.PUT).build()
                ))
                .build());
        String subject = "Dane użytkownika zmienione";
        String emailBody =
                "Stare dane: " + oldData.getName() + ", " + oldData.getEmail() + "\nNowe dane: " + user.getName() +
                        ", " + user.getEmail() + "\nID:" + user.getId();
        sesClient.sendEmail(createEmail(oldData, subject, emailBody));

        if (!user.getEmail().equals(oldData.getEmail())) {
            sesClient.sendEmail(createEmail(user, subject, emailBody));
        }

        return response(200, mapper.writeValueAsString(user));
    }

    private APIGatewayProxyResponseEvent deleteUser(String id) {
        db.deleteItem(DeleteItemRequest.builder()
                .tableName(USERS_TABLE)
                .key(Map.of(ID, AttributeValue.fromS(id)))
                .build());
        return response(204, "");
    }

    private APIGatewayProxyResponseEvent response(int code, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
    }

    private User getUserFormDB(String id) {
        var item = db.getItem(GetItemRequest.builder()
                .tableName(USERS_TABLE)
                .key(Map.of(ID, AttributeValue.fromS(id)))
                .build()).item();
        if (item == null || item.isEmpty()) {
            return null;
        }
        User user = new User();
        user.setId(item.get(ID).s());
        user.setName(item.get(NAME).s());
        user.setEmail(item.get(EMAIL).s());
        return user;
    }
}

