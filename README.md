# DevZen Shownote Generator

![DevZen themes](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_2.png)
![Card to start recording](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_1.png)
![A theme with links](https://raw.githubusercontent.com/SBozhko/devzen-shownote-generator/master/dz_3.png)

## Env Vars for Heroku 

### API keys and Tokens

GITTER_ACCESS_TOKEN  
TRELLO_APP_KEY  
TRELLO_READ_TOKEN  

### Other ids
TRELLO_DISCUSSED_LIST_ID  
TRELLO_TO_DISCUSS_LIST_ID  
TRELLO_IN_DISCUSSION_LIST_ID  
TRELLO_RECORDING_STARTED_CARD_ID  
TRELLO_BACKLOG_LIST_ID  
GITTER_DEVZEN_ROOM_ID

## How to get API keys
### TRELLO_APP_KEY 
Get Trello API Key from https://trello.com/app-key
### TRELLO_READ_TOKEN
Get https://trello.com/1/authorize?expiration=never&name=SinglePurposeToken&key=REPLACEWITHYOURKEY&scope=read&response_type=token  
### GITTER_ACCESS_TOKEN
1. Go to https://developer.gitter.im/apps
2. Sign in
3. Create new App https://developer.gitter.im/apps  
Specify redirect URL as `https://{your_heroku_app_domain}/trellohook`  
As a result you get OAUTH KEY and OAUTH SECRET
4. Go to https://developer.gitter.im/docs/authentication
5. GET `https://gitter.im/login/oauth/authorize?client_id={OAUTH KEY}&response_type=code&redirect_uri=https://{your_heroku_app_domain}/trellohook`  
6. Approve your app  
7. Check URL. You should get the code `https://{your_heroku_app_domain}/trellohook?code={your_code}`  
8. Exchange the code for an access token  
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
9. Your response:
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

