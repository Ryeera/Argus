# Argus
Hi there, I am Argus!
I am here to keep a close eye on your voice-channels!  

When you add me to your server, I will add a text-channel for each voice-channel, where people can chat about stuff in said voice-channel. You know, for all those YouTube-links, images, memes and so on talked about in the conversation.  

But here comes the catch: These text-channels are invisible until you actually join the voice-channel! This has the upside, that nobody can see or write in the text-channel, unless they are in the corresponding voice-channel (or unless you're an admin, sorry I can't do anything about that ^^").  

And if that wasn't enough yet, I can also create temporary voice-channels with just one command and log the joins and leaves in the text-channel!

## Why Admin-permissions?


## Setup
When you invite me to your server, I will make a new channel, which only admins can see, and explain everything there. Don't worry, you can delete it when we're done.

## Commands
All commands need to be preceded with the prefix you set. If you haven't set one, the default prefix is `a!`.

`a!help` - shows a help-embed with all commands.

`a!temp [name]` - creates a temporary VC with the given name.

`a!settings` - Shows the current settings and tells you how to change them.

`a!resync` - manually resyncs your server. Use this if you notice that someone is missing their permissions to see the text-channel they should see.

## FAQ
**Why does Argus need Administrator permissions?**
Discord's permissions are REALLY weird. If I try to add a permission-override in a channel that is in a hidden category, I need explicit "Manage Permissions"-permission in this text-channel. And I can only give me that permission if I already have it. Or have admin-permissions. So you don't have to give me admin-permissions, you unfortunately have to manually edit every text-channel I create. If you don't want to give me admin-permissions and are still wondering why I need the other permissions, keep reading!

**Why does Argus need the Manage Roles permission?**  
Manage Roles allows me to change permission-overrides in Text-Channels. I won't change anything in your role-system, and if you don't trust me, you can move my role all the way to the bottom, I'll still do my thing but I can't change any roles above mine ^^

**Why does Argus need the Manage Channels permission?**  
I need this role to create temporary voice-channels.

**Why does Argus need the Move Members perimission?**  
Although this feature is not implemented yet, I will be able to move members into their temporary voice-channel if they create one while connected to another voice-channel.

**Why does Argus not work with some channels?**  
If that happens, please join my Discord-Server below, so Ryeera can help you. It shouldn't happen, but if it does, please tell him.

## Planned Features
- Private Voice-Channels
- Command to make it so a voice-channel doesn't have an associated text-channel anymore / blacklist
- More Options
  - Instead of assigning permissions to an existing text-channel, create a new text-channel (more organized for admins)
  - Format Logging
  - Create Master-text-channel for copying permissions (for example so that mods can always see the text-channels)
- Support for bots like Server Stats that use voice-channels in a way that means that Argus shouldn't create a text-channel for them.
