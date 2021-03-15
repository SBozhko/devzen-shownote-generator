# DevZen Shownote Generator

![DevZen themes](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_2.png)
![Card to start recording](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_1.png)
![A theme with links](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_3.png)

## Env Vars for Heroku 

### API keys and Tokens

TRELLO_APP_KEY
TRELLO_READ_TOKEN
TELEGRAM_BOT_TOKEN

### Other ids
TRELLO_DISCUSSED_LIST_ID  
TRELLO_TO_DISCUSS_LIST_ID  
TRELLO_IN_DISCUSSION_LIST_ID  
TRELLO_RECORDING_STARTED_CARD_ID  
TRELLO_BACKLOG_LIST_ID

## How to get Trello keys
#### TRELLO_APP_KEY 
Get Trello API Key from https://trello.com/app-key
#### TRELLO_READ_TOKEN
Get https://trello.com/1/authorize?expiration=never&name=DevZenShownotesGen&key=TRELLO_APP_KEY&scope=read&response_type=token

## How to register a webhook in Trello
```
curl -X POST -H "Content-Type: application/json" \
https://api.trello.com/1/tokens/{TRELLO_READ_TOKEN}/webhooks/ \
-d '{
  "key": "{TRELLO_APP_KEY}",
  "callbackURL": "https://{your_heroku_app_domain}/trellohook",
  "idModel":"{TRELLO_IN_DISCUSSION_LIST_ID}",
  "description": "DevZen_Webhook"
}'
```
Read more: https://developer.atlassian.com/cloud/trello/guides/rest-api/webhooks/

## How to get TELEGRAM_BOT_TOKEN
https://core.telegram.org/bots#creating-a-new-bot
