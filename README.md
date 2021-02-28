# DevZen Shownote Generator

![DevZen themes](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_2.png)
![Card to start recording](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_1.png)
![A theme with links](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_3.png)

## Env Vars for Heroku 

### API keys and Tokens

GITTER_ACCESS_TOKEN  
TRELLO_APP_KEY  
TRELLO_READ_TOKEN
TELEGRAM_BOT_TOKEN

### Other ids
TRELLO_DISCUSSED_LIST_ID  
TRELLO_TO_DISCUSS_LIST_ID  
TRELLO_IN_DISCUSSION_LIST_ID  
TRELLO_RECORDING_STARTED_CARD_ID  
TRELLO_BACKLOG_LIST_ID  
GITTER_DEVZEN_ROOM_ID

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
  "description": "DevZen_Post2Gitter_Webhook"
}'
```
Read more: https://developer.atlassian.com/cloud/trello/guides/rest-api/webhooks/


## How to get GITTER_ACCESS_TOKEN 
1. Go to https://developer.gitter.im/apps
2. Sign in
3. Create a new application here https://developer.gitter.im/apps and specify redirect URL as `https://{your_heroku_app_domain}/trellohook`. 
You should get OAUTH KEY and OAUTH SECRET as a result.
4. Go to https://developer.gitter.im/docs/authentication
5. GET `https://gitter.im/login/oauth/authorize?client_id={OAUTH KEY}&response_type=code&redirect_uri=https://{your_heroku_app_domain}/trellohook`  
6. Approve your app.  
7. You will be redirected and should be able to see your code `https://{your_heroku_app_domain}/trellohook?code={your_code}`  
8. Exchange the code for GITTER_ACCESS_TOKEN  
POST https://gitter.im/login/oauth/token  
JSON body:  
```
{
	"client_id" : {OAUTH KEY},
	"client_secret" : {OAUTH SECRET}, 
        "code" : {your_code},
	"redirect_uri" : "https://{your_heroku_app_domain}/trellohook",
	"grant_type" : "authorization_code"
}
```
9. Your response should look like:
```
{
	"access_token": {your_gitter_access_token},
	"token_type": "Bearer"
}
```
GITTER_ACCESS_TOKEN == access_token  
10. Check if it works 
```
$ curl -i -H "Accept: application/json" -H "Authorization: Bearer {your_gitter_access_token}" "https://api.gitter.im/v1/user/me"
```

## How to get TELEGRAM_BOT_TOKEN
https://core.telegram.org/bots#creating-a-new-bot
