import fs from 'fs';
import path from 'path';
import express, { Request, Response } from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import axios from 'axios';
import { QuestTool, Duradel, Nieve, KonarQuoMaten } from 'osrs-tools';
// @ts-ignore
import hiscores from 'osrs-json-hiscores';

dotenv.config();

const app = express();
const port = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const USER_AGENT = 'BobBot OSRS API - @yourdiscord';

// --- Hiscores ---
app.get('/api/player/:username', async (req: Request, res: Response) => {
    try {
        const { username } = req.params;
        const stats = await hiscores.getStats(username);
        res.json(stats);
    } catch (error: any) {
        if (error.message && error.message.includes('404')) {
            res.status(404).json({ error: 'Player not found' });
        } else {
            res.status(500).json({ error: error.message });
        }
    }
});

// --- Items & Prices ---
// Using OSRS Wiki Prices API directly as in the previous Java version
app.get('/api/item/:query', async (req: Request, res: Response) => {
    try {
        const { query } = req.params;
        const mappingRes = await axios.get('https://prices.runescape.wiki/api/v1/osrs/mapping', {
            headers: { 'User-Agent': USER_AGENT }
        });
        const mapping = mappingRes.data;

        const item = mapping.find((i: any) => i.name.toLowerCase() === query.toLowerCase()) 
                  || mapping.find((i: any) => i.name.toLowerCase().startsWith(query.toLowerCase()));

        if (!item) return res.status(404).json({ error: 'Item not found' });

        const priceRes = await axios.get(`https://prices.runescape.wiki/api/v1/osrs/latest?id=${item.id}`, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        res.json({
            id: item.id,
            name: item.name,
            prices: priceRes.data.data[item.id]
        });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

app.get('/api/items/search/:query', async (req: Request, res: Response) => {
    try {
        const { query } = req.params;
        const limit = parseInt(req.query.limit as string) || 10;
        const mappingRes = await axios.get('https://prices.runescape.wiki/api/v1/osrs/mapping', {
            headers: { 'User-Agent': USER_AGENT }
        });
        const mapping = mappingRes.data;
        const results = mapping
            .filter((i: any) => i.name.toLowerCase().includes(query.toLowerCase()))
            .sort((a: any, b: any) => a.name.length - b.name.length)
            .slice(0, limit);
        res.json(results);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Wiki ---
app.get('/api/wiki/:title', async (req: Request, res: Response) => {
    try {
        const { title } = req.params;
        const url = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=${encodeURIComponent(title)}`;
        const response = await axios.get(url, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        const pages = response.data.query.pages;
        const pageId = Object.keys(pages)[0];
        const extract = pages[pageId].extract;

        res.json({
            title,
            url: `https://oldschool.runescape.wiki/w/${encodeURIComponent(title).replace(/%20/g, '_')}`,
            summary: extract ? extract.split('\n')[0] : null,
            extract: extract || null
        });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

app.get('/api/wiki/guide/:title', async (req: Request, res: Response) => {
    try {
        const { title } = req.params;
        // Try to fetch the quick guide first
        const quickGuideTitle = `${title}/Quick guide`;
        const url = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&explaintext&format=json&titles=${encodeURIComponent(quickGuideTitle)}`;
        let response = await axios.get(url, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        let pages = response.data.query.pages;
        let pageId = Object.keys(pages)[0];
        let extract = pages[pageId].extract;

        let finalUrl = `https://oldschool.runescape.wiki/w/${encodeURIComponent(title).replace(/%20/g, '_')}`;

        // If no quick guide, try the main page
        if (!extract || pageId === "-1") {
            const mainUrl = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&explaintext&format=json&titles=${encodeURIComponent(title)}`;
            response = await axios.get(mainUrl, {
                headers: { 'User-Agent': USER_AGENT }
            });
            pages = response.data.query.pages;
            pageId = Object.keys(pages)[0];
            extract = pages[pageId].extract;
        } else {
            finalUrl += "/Quick_guide";
        }

        res.json({
            title,
            url: finalUrl,
            guide: extract || null
        });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

app.get('/api/wiki/search/:query', async (req: Request, res: Response) => {
    try {
        const { query } = req.params;
        const searchUrl = `https://oldschool.runescape.wiki/api.php?action=query&list=search&srsearch=${encodeURIComponent(query)}&format=json`;
        const searchRes = await axios.get(searchUrl, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        const results = searchRes.data.query.search;
        if (results && results.length > 0) {
            const bestMatch = results[0].title;
            const summaryUrl = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&exintro&explaintext&format=json&redirects=1&titles=${encodeURIComponent(bestMatch)}`;
            const summaryRes = await axios.get(summaryUrl, {
                headers: { 'User-Agent': USER_AGENT }
            });
            
            const pages = summaryRes.data.query.pages;
            const pageId = Object.keys(pages)[0];
            if (pageId !== "-1") {
                const page = pages[pageId];
                return res.json({
                    title: page.title,
                    url: `https://oldschool.runescape.wiki/w/${encodeURIComponent(page.title).replace(/%20/g, '_')}`,
                    summary: page.extract ? page.extract.split('\n')[0] : null
                });
            }
        }
        res.status(404).json({ error: 'No results found' });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Quests ---
app.get('/api/quests/:name', async (req: Request, res: Response) => {
    try {
        const { name } = req.params;
        let quest = QuestTool.getQuestByName(name);
        
        // 1. Try fuzzy search in osrs-tools files if not found exactly
        if (!quest) {
            try {
                const questPath = require.resolve('osrs-tools');
                const questDir = path.join(path.dirname(questPath), 'model/quest/all');
                if (fs.existsSync(questDir)) {
                    const files = fs.readdirSync(questDir).filter(f => f.endsWith('.js') && !f.endsWith('.map'));
                    const normalizedQuery = name.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                    
                    for (const file of files) {
                        const questFileName = file.replace('.js', '');
                        if (questFileName.toLowerCase().includes(normalizedQuery) || 
                            normalizedQuery.includes(questFileName.toLowerCase())) {
                            quest = QuestTool.getQuestByName(questFileName);
                            if (quest) break;
                        }
                    }
                }
            } catch (e) {
                console.error('Fuzzy quest search failed:', e);
            }
        }

        // 2. Wiki fallback as a last resort
        if (!quest) {
            const searchUrl = `https://oldschool.runescape.wiki/api.php?action=query&list=search&srsearch=${encodeURIComponent(name)}&format=json`;
            const searchRes = await axios.get(searchUrl, {
                headers: { 'User-Agent': USER_AGENT }
            });
            
            const results = searchRes.data.query.search;
            if (results && results.length > 0) {
                const bestMatch = results[0].title;
                const summaryUrl = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&exintro&explaintext&format=json&redirects=1&titles=${encodeURIComponent(bestMatch)}`;
                const summaryRes = await axios.get(summaryUrl, {
                    headers: { 'User-Agent': USER_AGENT }
                });
                
                const pages = summaryRes.data.query.pages;
                const pageId = Object.keys(pages)[0];
                if (pageId !== "-1") {
                    const page = pages[pageId];
                    quest = {
                        name: page.title,
                        url: `https://oldschool.runescape.wiki/w/${encodeURIComponent(page.title).replace(/%20/g, '_')}`,
                        description: page.extract ? page.extract.split('\n')[0] : '',
                        difficulty: 'Unknown (Wiki Fallback)',
                        length: 'Unknown',
                        questPoints: 0,
                        requirements: { skills: [], quests: [] },
                        rewards: { experience: [], items: [], unlocks: [] }
                    } as any;
                }
            }
        }

        if (!quest) {
            return res.status(404).json({ error: 'Quest not found' });
        }
        res.json(quest);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Slayer (New) ---
app.get('/api/slayer/:master', (req: Request, res: Response) => {
    try {
        const { master } = req.params;
        let tasks;
        switch (master.toLowerCase()) {
            case 'duradel': tasks = Duradel.tasks; break;
            case 'nieve': tasks = Nieve.tasks; break;
            case 'konar': tasks = KonarQuoMaten.tasks; break;
            default:
                return res.status(404).json({ error: 'Slayer master not found' });
        }
        res.json(tasks);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Health ---
app.get('/health', (req: Request, res: Response) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.listen(port, () => {
    console.log(`OSRS API listening at http://localhost:${port}`);
});
